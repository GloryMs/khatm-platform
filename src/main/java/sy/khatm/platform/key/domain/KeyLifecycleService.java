package sy.khatm.platform.key.domain;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.key.api.IssuerKeySummaryView;
import sy.khatm.platform.key.api.JwksLookup;
import sy.khatm.platform.key.api.PublicKeyHandle;
import sy.khatm.platform.key.api.PublishedKeyView;
import sy.khatm.platform.key.api.SignResult;
import sy.khatm.platform.key.api.TenantKeyProvisioner;
import sy.khatm.platform.key.persistence.IssuerKeyRepository;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Owns the {@code issuer_key} lifecycle: state transitions, the one-{@code ACTIVE}-per-tenant
 * invariant, and the {@link KeyProvider} that performs the underlying crypto (spec FS-0.5 §5).
 *
 * <p>This is the class {@link KeyBootstrap} and the {@code key :: api} implementations ({@code
 * KeySignerImpl}, {@code KeyVerifierImpl}) talk to — never {@link KeyProvider} directly — so that
 * swapping the provider (D3) never touches lifecycle or persistence logic.
 *
 * <p>{@link #rotate} is fully implemented here (the table and one-active index have existed since
 * the KH-0.2.1 baseline) but is deliberately not wired to any REST endpoint yet — it is called by
 * tests only. Administrative rotation arrives with RBAC (KH-2.2); scheduled rotation and the
 * runbook remain KH-2.3.2 (spec FS-0.5 §5).
 *
 * <p>{@link #listAllStatuses} (KH-1.1.5-BE, spec FS-1.5.4 #4) is a read-only lifecycle view — every
 * key regardless of state, no JWK material — for {@code key.web.SigningKeyStatusController}. No new
 * {@code key :: api} surface: the controller lives inside this module, reading this module's own
 * data directly.
 *
 * <p>Module-private.
 */
@Service
public class KeyLifecycleService implements TenantKeyProvisioner, JwksLookup {

  private static final String STATE_ACTIVE = "ACTIVE";
  private static final String STATE_RETIRING = "RETIRING";
  private static final String STATE_RETIRED = "RETIRED";
  private static final List<String> PUBLISHABLE_STATES = List.of(STATE_ACTIVE, STATE_RETIRING);

  private final IssuerKeyRepository repository;
  private final KeyProvider provider;
  private final AuditService audit;
  private final String providerName;

  KeyLifecycleService(
      IssuerKeyRepository repository,
      KeyProvider provider,
      AuditService audit,
      @Value("${khatm.keys.provider:SOFT}") String providerName) {
    this.repository = repository;
    this.provider = provider;
    this.audit = audit;
    this.providerName = providerName;
  }

  /**
   * Provision the tenant's first {@code ACTIVE} key if none exists yet.
   *
   * <p>Idempotent: a second call with an {@code ACTIVE} key already present does nothing and
   * returns {@link Optional#empty()} (spec FS-0.5 §5/§8.7).
   *
   * @param tenantId the tenant to bootstrap
   * @param tenantSlug the tenant's slug, used to build the new key's {@code kid}
   * @return the newly created key's summary, or {@link Optional#empty()} if an {@code ACTIVE} key
   *     already existed
   */
  @Transactional
  Optional<IssuerKeySummary> bootstrapIfNeeded(UUID tenantId, String tenantSlug) {
    if (repository.findByTenantIdAndState(tenantId, STATE_ACTIVE).isPresent()) {
      return Optional.empty();
    }
    IssuerKey created = createActiveKey(tenantId, tenantSlug);
    audit.record(AuditAction.KEY_CREATED, "issuer_key", created.getKid(), null);
    return Optional.of(toSummary(created));
  }

  /**
   * Retire the tenant's current {@code ACTIVE} key (if any) to {@code RETIRING} and activate a
   * freshly generated one.
   *
   * <p>The retiring update runs as an immediate bulk statement ({@link
   * IssuerKeyRepository#retireActive}) strictly before the new key is inserted, so the {@code
   * issuer_key_one_active} partial unique index never sees two {@code ACTIVE} rows for the same
   * tenant at once (spec FS-0.5 §5, DoD #4).
   *
   * @param tenantId the tenant to rotate
   * @param tenantSlug the tenant's slug, used to build the new key's {@code kid}
   * @return the newly created, now-{@code ACTIVE} key's summary
   */
  @Transactional
  IssuerKeySummary rotate(UUID tenantId, String tenantSlug) {
    repository.retireActive(tenantId, Instant.now());
    IssuerKey created = createActiveKey(tenantId, tenantSlug);
    audit.record(AuditAction.KEY_ROTATED, "issuer_key", created.getKid(), null);
    return toSummary(created);
  }

  /**
   * Sign a claim set with the tenant's current {@code ACTIVE} key.
   *
   * @param tenantId the tenant whose active key should sign
   * @param claims the claim set to sign
   * @return the signed result
   * @throws JOSEException if signing fails
   * @throws IllegalStateException if the tenant has no {@code ACTIVE} key (should not happen once
   *     {@link KeyBootstrap} has run)
   */
  @Transactional(readOnly = true)
  SignResult signWithActiveKey(UUID tenantId, JWTClaimsSet claims) throws JOSEException {
    IssuerKey active =
        repository
            .findByTenantIdAndState(tenantId, STATE_ACTIVE)
            .orElseThrow(
                () -> new IllegalStateException("No ACTIVE issuer key for tenant " + tenantId));
    return provider.sign(active.getProviderRef(), active.getKid(), claims);
  }

  /**
   * Resolve the public key for a {@code kid}, strictly — no fallback to the current active key.
   *
   * <p>An unknown {@code kid}, or one belonging to a {@code RETIRED} key, both resolve to {@link
   * Optional#empty()} (spec FS-0.5 §4, DoD #3).
   *
   * @param kid the key id to resolve
   * @return the public key handle, or empty if {@code kid} is unknown or {@code RETIRED}
   */
  @Transactional(readOnly = true)
  Optional<PublicKeyHandle> resolvePublicKey(String kid) {
    return repository
        .findByKid(kid)
        .filter(k -> !STATE_RETIRED.equals(k.getState()))
        .flatMap(k -> provider.publicKey(k.getProviderRef(), k.getKid()));
  }

  /**
   * List the tenant's publicly publishable keys ({@code ACTIVE} + {@code RETIRING}) for the legacy,
   * default-tenant-only JWKS endpoint (spec FS-0.5 §6, {@code key.web.JwksController}). {@code
   * RETIRED} keys are never published.
   *
   * <p>Named distinctly from the {@link JwksLookup#publishableKeys(UUID)} cross-module override
   * below — same underlying query, different return type ({@link PublishedKey} is module-private;
   * {@link PublishedKeyView} is the {@code key :: api} shape other modules see).
   *
   * @param tenantId the tenant to list keys for
   * @return the publishable keys, public JWK material only
   */
  @Transactional(readOnly = true)
  public List<PublishedKey> publishableKeysForDefaultTenantJwks(UUID tenantId) {
    return repository.findByTenantIdAndStateIn(tenantId, PUBLISHABLE_STATES).stream()
        .map(k -> new PublishedKey(k.getKid(), k.getPublicJwkJson()))
        .toList();
  }

  /**
   * List every signing key for a tenant regardless of state, newest first (spec FS-1.5.4 #4, {@code
   * GET /api/v1/admin/signing-keys}) — lifecycle fields only, never the public JWK or any private
   * material.
   *
   * @param tenantId the tenant to list keys for
   * @return every key's lifecycle status, including {@code RETIRED} keys
   */
  @Transactional(readOnly = true)
  public List<IssuerKeyStatusView> listAllStatuses(UUID tenantId) {
    return repository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
        .map(
            k ->
                new IssuerKeyStatusView(k.getKid(), k.getState(), k.getValidFrom(), k.getValidTo()))
        .toList();
  }

  /**
   * Ensure {@code tenantId} has an {@code ACTIVE} signing key (spec FS-2.1 D6), for the tenant
   * onboarding admin plane. Idempotent — returns the existing {@code ACTIVE} key's summary if one
   * already exists, rather than creating a second one or throwing (the resumable-onboarding path,
   * spec V3).
   *
   * @param tenantId the tenant to provision
   * @param tenantSlug the tenant's slug, used to build the new key's {@code kid}
   * @return the tenant's {@code ACTIVE} key summary
   */
  @Override
  @Transactional
  public IssuerKeySummaryView provisionFirstKey(UUID tenantId, String tenantSlug) {
    Optional<IssuerKeySummary> created = bootstrapIfNeeded(tenantId, tenantSlug);
    IssuerKeySummary summary =
        created.orElseGet(
            () ->
                toSummary(
                    repository
                        .findByTenantIdAndState(tenantId, STATE_ACTIVE)
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "No ACTIVE issuer key for tenant "
                                        + tenantId
                                        + " immediately after bootstrapIfNeeded reported one"
                                        + " already existed"))));
    return new IssuerKeySummaryView(summary.kid(), summary.state(), summary.validFrom());
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasActiveKey(UUID tenantId) {
    return repository.findByTenantIdAndState(tenantId, STATE_ACTIVE).isPresent();
  }

  /**
   * List a tenant's publishable ({@code ACTIVE} + {@code RETIRING}) keys for {@code
   * tenant.web.TenantJwksController}'s per-tenant JWKS endpoint (spec FS-2.1 D8) — the same
   * underlying query as {@link #publishableKeysForDefaultTenantJwks(UUID)}, mapped to the
   * cross-module view type.
   *
   * @param tenantId the tenant to list keys for
   * @return the publishable keys, public JWK material only
   */
  @Override
  @Transactional(readOnly = true)
  public List<PublishedKeyView> publishableKeys(UUID tenantId) {
    return repository.findByTenantIdAndStateIn(tenantId, PUBLISHABLE_STATES).stream()
        .map(k -> new PublishedKeyView(k.getKid(), k.getPublicJwkJson()))
        .toList();
  }

  private IssuerKey createActiveKey(UUID tenantId, String tenantSlug) {
    long seq = repository.countByTenantId(tenantId) + 1;
    String kid = tenantSlug + ":key-" + seq;
    GeneratedKeyMaterial material = provider.generate(kid);

    Instant now = Instant.now();
    IssuerKey key = new IssuerKey();
    key.setId(Uuidv7.generate());
    key.setTenantId(tenantId);
    key.setKid(material.kid());
    key.setAlgo("ES256");
    key.setPublicJwkJson(material.publicJwkJson());
    key.setProvider(providerName);
    key.setProviderRef(material.providerRef());
    key.setState(STATE_ACTIVE);
    key.setValidFrom(now);
    key.setCreatedAt(now);
    return repository.save(key);
  }

  private static IssuerKeySummary toSummary(IssuerKey key) {
    return new IssuerKeySummary(key.getKid(), key.getState(), key.getValidFrom());
  }
}
