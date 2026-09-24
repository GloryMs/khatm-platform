package sy.khatm.platform.issuerclient.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditPrincipal;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;

/**
 * The issuer-client lifecycle: create, rotate, suspend, resume, revoke (spec FS-2.7a D1/D10/D11).
 *
 * <pre>
 *   ACTIVE ──suspend──► SUSPENDED ──resume──► ACTIVE
 *   ACTIVE ──rotate───► RETIRING ──(retire_after passes)──► REVOKED
 *   ACTIVE | SUSPENDED | RETIRING ──revoke──► REVOKED   (final)
 * </pre>
 *
 * <p><b>Rotation without an outage:</b> {@link #rotate} mints a <em>new</em> client (own prefix,
 * own hash, {@code rotated_from} = the old one, same schemas/expiry) and moves the old to {@code
 * RETIRING} with {@code retire_after = now + hours}; the old key keeps working until then, so a
 * connector can be re-keyed live. {@code RetiringSweeper} finishes the job. Only an {@code ACTIVE}
 * client can be rotated, which is what keeps one active client per rotation chain.
 *
 * <p>Everything runs under the ambient {@link TenantContext} (RLS scopes every read/write to it);
 * the parent-organisation plane reaches a child by switching that context through {@code
 * shared.OnBehalfOfExecutor}, never by any other route. Every state change writes its audit row in
 * the same transaction ({@code entityRef} = the key prefix — never key material).
 */
@Service
public class IssuerClientLifecycle {

  private static final Duration NO_WINDOW = Duration.ZERO;

  private final IssuerClientRepository repository;
  private final ApiKeyGenerator keys;
  private final HolderSecretProvisioner holderSecrets;
  private final SchemaCatalog schemas;
  private final AuditService audit;
  private final Clock clock;
  private final int defaultRetireHours;
  private final int maxRetireHours;

  IssuerClientLifecycle(
      IssuerClientRepository repository,
      ApiKeyGenerator keys,
      HolderSecretProvisioner holderSecrets,
      SchemaCatalog schemas,
      AuditService audit,
      Clock clock,
      @Value("${khatm.issuer.rotation.default-retire-hours:24}") int defaultRetireHours,
      @Value("${khatm.issuer.rotation.max-retire-hours:72}") int maxRetireHours) {
    this.repository = repository;
    this.keys = keys;
    this.holderSecrets = holderSecrets;
    this.schemas = schemas;
    this.audit = audit;
    this.clock = clock;
    this.defaultRetireHours = defaultRetireHours;
    this.maxRetireHours = maxRetireHours;
  }

  /**
   * Create a client for the ambient tenant.
   *
   * @param name bilingual display name (both languages required)
   * @param allowedSchemaIds schemas the client may issue against; each must exist in this tenant
   * @param expiresAt optional hard expiry, must be in the future
   * @return the client plus its one-time key (and holder secret when this call generated it)
   */
  @Transactional
  public CreatedIssuerClient create(
      LocalizedText name, List<UUID> allowedSchemaIds, Instant expiresAt) {
    requireName(name);
    Set<UUID> schemaIds = requireSchemas(allowedSchemaIds);
    if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
      throw new ValidationException(
          ErrorCode.KH_ICL_0400, "issuer-client.validation-failed", "expiresAt");
    }
    UUID tenantId = TenantContext.current();
    Builder created = new Builder(tenantId, name, schemaIds, expiresAt, null).persist();
    // Last step before commit: a Vault failure rolls the whole creation back (fail-closed), so a
    // secret can never be generated for a client that does not exist.
    String holderSecret = holderSecrets.provisionIfRoot(tenantId).orElse(null);
    return new CreatedIssuerClient(created.view, created.rawKey, holderSecret, null);
  }

  /**
   * Rotate {@code id}: mint its replacement and put it into a grace window.
   *
   * @param id the client to rotate (must be {@code ACTIVE})
   * @param retireAfterHours grace window in hours, {@code 0..max}; {@code null} = the configured
   *     default (24)
   * @return the replacement client with its one-time key and the id of the now-retiring client
   */
  @Transactional
  public CreatedIssuerClient rotate(UUID id, Integer retireAfterHours) {
    int hours = retireAfterHours == null ? defaultRetireHours : retireAfterHours;
    if (hours < 0 || hours > maxRetireHours) {
      throw new ValidationException(
          ErrorCode.KH_ICL_0400, "issuer-client.validation-failed", "retireAfterHours");
    }
    IssuerClient old = load(id);
    if (!IssuerClient.STATUS_ACTIVE.equals(old.getStatus())) {
      throw invalidTransition();
    }
    Instant now = clock.instant();
    Instant retireAfter = now.plus(hours == 0 ? NO_WINDOW : Duration.ofHours(hours));
    List<UUID> schemaIds = repository.findAllowedSchemaIds(old.getId());

    Builder builder =
        new Builder(
            old.getTenantId(),
            old.getNameI18n(),
            new HashSet<>(schemaIds),
            old.getExpiresAt(),
            old.getId());
    IssuerClientView replacement = builder.persist().view;

    old.setStatus(IssuerClient.STATUS_RETIRING);
    old.setRetireAfter(retireAfter);
    repository.save(old);
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("newPrefix", replacement.keyPrefix());
    detail.put("retireAfter", retireAfter.toString());
    audit.record(AuditAction.ISSUER_CLIENT_ROTATED, "issuer_client", old.getKeyPrefix(), detail);
    return new CreatedIssuerClient(replacement, builder.rawKey, null, old.getId());
  }

  /** Suspend an {@code ACTIVE} client: its key is rejected until {@link #resume}. */
  @Transactional
  public IssuerClientView suspend(UUID id) {
    IssuerClient client = load(id);
    requireStatus(client, IssuerClient.STATUS_ACTIVE);
    client.setStatus(IssuerClient.STATUS_SUSPENDED);
    repository.save(client);
    audit.record(AuditAction.ISSUER_CLIENT_SUSPENDED, "issuer_client", client.getKeyPrefix(), null);
    return toView(client);
  }

  /** Resume a {@code SUSPENDED} client. */
  @Transactional
  public IssuerClientView resume(UUID id) {
    IssuerClient client = load(id);
    requireStatus(client, IssuerClient.STATUS_SUSPENDED);
    client.setStatus(IssuerClient.STATUS_ACTIVE);
    repository.save(client);
    audit.record(AuditAction.ISSUER_CLIENT_RESUMED, "issuer_client", client.getKeyPrefix(), null);
    return toView(client);
  }

  /** Revoke a client permanently (from any non-revoked status). */
  @Transactional
  public IssuerClientView revoke(UUID id) {
    IssuerClient client = load(id);
    if (IssuerClient.STATUS_REVOKED.equals(client.getStatus())) {
      throw invalidTransition();
    }
    client.setStatus(IssuerClient.STATUS_REVOKED);
    repository.save(client);
    audit.record(
        AuditAction.ISSUER_CLIENT_REVOKED,
        "issuer_client",
        client.getKeyPrefix(),
        Map.of("reason", "operator"));
    return toView(client);
  }

  /**
   * Finish a rotation: revoke {@code id} if it is still {@code RETIRING} and its window has ended.
   * Called per client by {@code RetiringSweeper} under that client's own tenant context.
   *
   * @return {@code true} if the client was revoked by this call
   */
  @Transactional
  public boolean finishRetirement(UUID id) {
    IssuerClient client = repository.findById(id).orElse(null);
    if (client == null
        || !IssuerClient.STATUS_RETIRING.equals(client.getStatus())
        || client.getRetireAfter() == null
        || client.getRetireAfter().isAfter(clock.instant())) {
      return false;
    }
    client.setStatus(IssuerClient.STATUS_REVOKED);
    repository.save(client);
    audit.record(
        AuditAction.ISSUER_CLIENT_REVOKED,
        "issuer_client",
        client.getKeyPrefix(),
        Map.of("reason", "retired"));
    return true;
  }

  /** Every client of the ambient tenant, newest first. */
  @Transactional(readOnly = true)
  public List<IssuerClientView> list() {
    return repository.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.current()).stream()
        .map(this::toView)
        .toList();
  }

  // ── internals ────────────────────────────────────────────────────────────────────────────

  private IssuerClient load(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.KH_ICL_0404, "issuer-client.not-found"));
  }

  private static void requireStatus(IssuerClient client, String required) {
    if (!required.equals(client.getStatus())) {
      throw invalidTransition();
    }
  }

  private static ConflictException invalidTransition() {
    return new ConflictException(ErrorCode.KH_ICL_1409, "issuer-client.invalid-transition");
  }

  private static void requireName(LocalizedText name) {
    if (name == null || name.en().isBlank() || name.ar().isBlank()) {
      throw new ValidationException(
          ErrorCode.KH_ICL_0400, "issuer-client.validation-failed", "name");
    }
  }

  /** Each id must resolve in the ambient tenant — RLS hides other tenants' schemas entirely. */
  private Set<UUID> requireSchemas(List<UUID> ids) {
    Set<UUID> result = new HashSet<>();
    if (ids != null) {
      for (UUID id : ids) {
        if (id == null || schemas.findById(id).isEmpty()) {
          throw new ValidationException(
              ErrorCode.KH_ICL_0400, "issuer-client.validation-failed", "allowedSchemaIds");
        }
        result.add(id);
      }
    }
    return result;
  }

  private IssuerClientView toView(IssuerClient c) {
    return new IssuerClientView(
        c.getId(),
        c.getTenantId(),
        c.getNameI18n(),
        c.getKeyPrefix(),
        List.of(c.getScopes()),
        c.getStatus(),
        repository.findAllowedSchemaIds(c.getId()),
        c.getRotatedFrom(),
        c.getRetireAfter(),
        c.getExpiresAt(),
        c.getLastUsedAt(),
        c.getCreatedAt());
  }

  private static UUID currentConsoleUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.isAuthenticated()
        && auth.getPrincipal() instanceof AuditPrincipal p
        && "USER".equals(p.auditActorType())) {
      return p.auditActorId();
    }
    return null;
  }

  /** Persists one new client + its allowlist + its CREATED audit row, holding the raw key. */
  private final class Builder {
    private final UUID tenantId;
    private final LocalizedText name;
    private final Set<UUID> schemaIds;
    private final Instant expiresAt;
    private final UUID rotatedFrom;
    private String rawKey;
    private IssuerClientView view;

    Builder(
        UUID tenantId,
        LocalizedText name,
        Set<UUID> schemaIds,
        Instant expiresAt,
        UUID rotatedFrom) {
      this.tenantId = tenantId;
      this.name = name;
      this.schemaIds = schemaIds;
      this.expiresAt = expiresAt;
      this.rotatedFrom = rotatedFrom;
    }

    Builder persist() {
      ApiKeyGenerator.Generated key = keys.generate();
      IssuerClient c = new IssuerClient();
      c.setId(Uuidv7.generate());
      c.setTenantId(tenantId);
      c.setNameI18n(name);
      c.setKeyPrefix(key.prefix());
      c.setApiKeyHash(key.secretHash());
      c.setScopes(new String[] {"issue"});
      c.setStatus(IssuerClient.STATUS_ACTIVE);
      c.setRotatedFrom(rotatedFrom);
      c.setExpiresAt(expiresAt);
      c.setCreatedBy(currentConsoleUserId());
      c.setCreatedAt(clock.instant());
      repository.saveAndFlush(c);
      for (UUID schemaId : schemaIds) {
        repository.insertAllowedSchema(c.getId(), schemaId);
      }
      audit.record(AuditAction.ISSUER_CLIENT_CREATED, "issuer_client", c.getKeyPrefix(), null);
      this.rawKey = key.rawKey();
      this.view = toView(c);
      return this;
    }
  }
}
