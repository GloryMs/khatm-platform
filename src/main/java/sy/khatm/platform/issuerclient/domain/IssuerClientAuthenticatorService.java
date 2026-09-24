package sy.khatm.platform.issuerclient.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import sy.khatm.platform.issuerclient.api.IssuerClientAuthenticator;
import sy.khatm.platform.issuerclient.api.IssuerClientPrincipal;
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

/**
 * Verifies presented {@code khi_} keys (spec FS-2.7a D2/D3/D11/D12).
 *
 * <p>Order of checks, chosen so an outsider learns nothing: syntax first (no hashing for garbage),
 * then prefix lookup, then the argon2id comparison — and only <em>after</em> the secret matches are
 * status, expiry, rotation window, and tenant state examined, so a wrong secret can never reveal
 * whether a client is suspended. An unknown prefix burns one dummy argon2 evaluation so it is not
 * distinguishable by timing from a known prefix with a wrong secret.
 *
 * <p>The lookup runs under {@link SystemAccessExecutor} — by construction there is no tenant yet
 * (resolving it is the point). {@code app.tenant_id} for the request is then set by {@code
 * rbac.security.TenantContextFilter} from the principal returned here, whose tenant is read from
 * the {@code issuer_client} row itself.
 *
 * <p>Every failure returns {@link Optional#empty()} and, at most once per minute per prefix (a
 * plain in-memory window — sufficient for the single-replica deployment; a shared limiter belongs
 * with per-client rate limiting, KH-2.5), writes an {@link AuditAction#ISSUER_CLIENT_AUTH_FAILED}
 * row carrying the prefix and the internal reason, never the secret. There is no automatic lockout
 * (D12): anyone who knows a prefix could otherwise silence a government issuer.
 */
@Service
public class IssuerClientAuthenticatorService implements IssuerClientAuthenticator {

  static final Duration AUTH_FAILURE_AUDIT_WINDOW = Duration.ofSeconds(60);
  static final Duration LAST_USED_GRANULARITY = Duration.ofSeconds(60);
  private static final int THROTTLE_MAP_CAP = 10_000;

  private final IssuerClientRepository repository;
  private final ApiKeyGenerator keys;
  private final AuditService audit;
  private final TenantDirectory tenants;
  private final SystemAccessExecutor systemAccess;
  private final Clock clock;
  private final Map<String, Long> lastFailureAudit = new ConcurrentHashMap<>();

  IssuerClientAuthenticatorService(
      IssuerClientRepository repository,
      ApiKeyGenerator keys,
      AuditService audit,
      TenantDirectory tenants,
      SystemAccessExecutor systemAccess,
      Clock clock) {
    this.repository = repository;
    this.keys = keys;
    this.audit = audit;
    this.tenants = tenants;
    this.systemAccess = systemAccess;
    this.clock = clock;
  }

  /** What a lookup produced: a principal, or an internal failure reason (never exposed). */
  private record Outcome(
      IssuerClientPrincipal principal, String reason, String prefix, UUID knownTenantId) {

    static Outcome ok(IssuerClientPrincipal principal) {
      return new Outcome(principal, null, null, null);
    }

    static Outcome fail(String reason, String prefix, UUID tenantId) {
      return new Outcome(null, reason, prefix, tenantId);
    }
  }

  @Override
  public Optional<IssuerClientPrincipal> resolve(String rawKey) {
    Optional<ApiKeyGenerator.Parsed> parsed = ApiKeyGenerator.parse(rawKey);
    if (parsed.isEmpty()) {
      recordFailure(Outcome.fail("malformed", null, null));
      return Optional.empty();
    }
    ApiKeyGenerator.Parsed key = parsed.get();
    Outcome outcome = systemAccess.runAsSystem(() -> verify(key));
    if (outcome.principal() == null) {
      recordFailure(outcome);
      return Optional.empty();
    }
    return Optional.of(outcome.principal());
  }

  private Outcome verify(ApiKeyGenerator.Parsed key) {
    IssuerClient client = repository.findByKeyPrefix(key.prefix()).orElse(null);
    if (client == null) {
      keys.spendEqualTime(key.secret());
      return Outcome.fail("unknown-prefix", key.prefix(), null);
    }
    if (!keys.matches(key.secret(), client.getApiKeyHash())) {
      return Outcome.fail("bad-secret", key.prefix(), client.getTenantId());
    }
    Instant now = clock.instant();
    String status = client.getStatus();
    if (IssuerClient.STATUS_SUSPENDED.equals(status)) {
      return Outcome.fail("suspended", key.prefix(), client.getTenantId());
    }
    if (IssuerClient.STATUS_REVOKED.equals(status)) {
      return Outcome.fail("revoked", key.prefix(), client.getTenantId());
    }
    if (IssuerClient.STATUS_RETIRING.equals(status)
        && client.getRetireAfter() != null
        && !now.isBefore(client.getRetireAfter())) {
      return Outcome.fail("retired", key.prefix(), client.getTenantId());
    }
    if (client.getExpiresAt() != null && !now.isBefore(client.getExpiresAt())) {
      return Outcome.fail("expired", key.prefix(), client.getTenantId());
    }
    if (!tenants.findById(client.getTenantId()).map(TenantRef::isActive).orElse(false)) {
      return Outcome.fail("tenant-suspended", key.prefix(), client.getTenantId());
    }
    repository.touchLastUsed(client.getId(), now, now.minus(LAST_USED_GRANULARITY));
    return Outcome.ok(
        new IssuerClientPrincipal(
            client.getId(),
            client.getTenantId(),
            new LinkedHashSet<>(Arrays.asList(client.getScopes()))));
  }

  private void recordFailure(Outcome failure) {
    String throttleKey = failure.prefix() == null ? "<malformed>" : failure.prefix();
    if (!allowAudit(throttleKey)) {
      return;
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    if (failure.prefix() != null) {
      detail.put("prefix", failure.prefix());
    }
    detail.put("reason", failure.reason());
    if ("suspended".equals(failure.reason()) || "revoked".equals(failure.reason())) {
      // D12: distinguished here, in the audit trail only — the response body is the generic 401.
      detail.put("code", ErrorCode.KH_ICL_0409.code());
    }
    // Attribute the row to the client's own tenant when it is known, else the default tenant
    // (exactly what ApiKeyAuthFilter's API_KEY_AUTH_FAILED already does for an unknown key).
    TenantRef tenant =
        failure.knownTenantId() == null
            ? null
            : tenants.findById(failure.knownTenantId()).orElse(null);
    if (tenant == null) {
      TenantContext.runAsDefaultTenant(
          () ->
              audit.record(
                  AuditAction.ISSUER_CLIENT_AUTH_FAILED,
                  "issuer_client",
                  failure.prefix(),
                  detail));
      return;
    }
    TenantContext.set(tenant.id(), tenant.slug());
    try {
      audit.record(
          AuditAction.ISSUER_CLIENT_AUTH_FAILED, "issuer_client", failure.prefix(), detail);
    } finally {
      TenantContext.clear();
    }
  }

  /** One audit row per key per window — atomic, so 50 concurrent failures still yield one. */
  private boolean allowAudit(String throttleKey) {
    long now = clock.millis();
    if (lastFailureAudit.size() > THROTTLE_MAP_CAP) {
      lastFailureAudit.clear();
    }
    boolean[] allowed = {false};
    lastFailureAudit.compute(
        throttleKey,
        (k, last) -> {
          if (last == null || now - last >= AUTH_FAILURE_AUDIT_WINDOW.toMillis()) {
            allowed[0] = true;
            return now;
          }
          return last;
        });
    return allowed[0];
  }
}
