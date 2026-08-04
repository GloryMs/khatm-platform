package sy.khatm.platform.key.domain;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.key.api.IssuerKeySummaryView;
import sy.khatm.platform.key.api.JwksLookup;
import sy.khatm.platform.key.api.PublicKeyHandle;
import sy.khatm.platform.key.api.PublishedKeyView;
import sy.khatm.platform.key.api.SignResult;
import sy.khatm.platform.key.api.TenantKeyProvisioner;
import sy.khatm.platform.key.events.KeyRotated;
import sy.khatm.platform.key.persistence.IssuerKeyRepository;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;

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
 * <p><b>Multi-provider routing (KH-2.3b, spec FS-2.3 D5/D6):</b> every registered {@link
 * KeyProvider} bean is injected as a name-keyed map (bean name {@code "SOFT"}/{@code "VAULT"}
 * matches the value stored in {@link IssuerKey#getProvider()}), never a single provider. Every
 * operation that needs to reach a backend — {@link #createActiveKey}, {@link #signWithActiveKey} —
 * resolves the provider from the row it is actually operating on, never from a single ambient
 * default, so a tenant that has migrated onto Vault and one still on SOFT are served correctly from
 * the very same running process, and a tenant's pre-migration SOFT keys stay signable/resolvable
 * after that migration. {@link #resolvePublicKey} does not consult a provider at all — see its own
 * Javadoc.
 *
 * <p>Module-private.
 */
@Service
public class KeyLifecycleService implements TenantKeyProvisioner, JwksLookup {

  private static final String STATE_ACTIVE = "ACTIVE";
  private static final String STATE_RETIRING = "RETIRING";
  private static final String STATE_RETIRED = "RETIRED";
  // Spec FS-0.2 §3.2 / FS-2.3 D2/D4: RETIRING and RETIRED both stay published — only a key that
  // was never issued (PENDING, not reachable by any code path today) would be excluded, and there
  // is no PENDING row to exclude in practice. Final trimming of long-retired keys out of JWKS is a
  // Phase 3 trust-bundle concern (spec D4), not this session's.
  private static final List<String> PUBLISHABLE_STATES =
      List.of(STATE_ACTIVE, STATE_RETIRING, STATE_RETIRED);

  private final IssuerKeyRepository repository;
  private final Map<String, KeyProvider> providers;
  private final AuditService audit;
  private final ApplicationEventPublisher eventPublisher;
  private final String defaultProviderName;
  private final Duration minRetiringAge;

  KeyLifecycleService(
      IssuerKeyRepository repository,
      Map<String, KeyProvider> providers,
      AuditService audit,
      ApplicationEventPublisher eventPublisher,
      @Value("${khatm.keys.provider:SOFT}") String defaultProviderName,
      @Value("${khatm.keys.min-retiring-age:P30D}") Duration minRetiringAge) {
    this.repository = repository;
    this.providers = providers;
    this.audit = audit;
    this.eventPublisher = eventPublisher;
    this.defaultProviderName = defaultProviderName;
    this.minRetiringAge = minRetiringAge;
  }

  /**
   * Resolve a registered {@link KeyProvider} bean by name (spec FS-2.3 D5/D6).
   *
   * @param providerName the provider name, e.g. {@code SOFT}/{@code VAULT}
   * @return the matching provider bean
   * @throws ValidationException {@code KH-KEY-0400} if no such provider is registered — either a
   *     typo or a provider this deployment never configured (e.g. {@code VAULT} without {@code
   *     khatm.keys.vault.enabled=true}). Deliberately never falls back to any other provider — a
   *     silent SOFT fallback for an unresolvable KMS-class provider would be a key-security
   *     downgrade (spec FS-2.3 D5/D6's fail-closed requirement).
   */
  private KeyProvider resolveProvider(String providerName) {
    KeyProvider resolved = providers.get(providerName);
    if (resolved == null) {
      throw new ValidationException(ErrorCode.KH_KEY_0400, "key.unknown-provider", providerName);
    }
    return resolved;
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
    // Spec FS-2.3 D5/D6, veto V3: every tenant is born on the platform-default provider (SOFT) —
    // starting a tenant directly on a KMS-class provider is out of scope; migrating there is
    // always a later, explicit rotation (see #rotate(UUID, String, String)).
    IssuerKey created = createActiveKey(tenantId, tenantSlug, defaultProviderName);
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
   * tenant at once (spec FS-0.5 §5, DoD #4) — this is also the race arbiter for concurrent
   * rotations: two simultaneous callers both attempt this same sequence, and Postgres row-level
   * locking on the {@code retireActive} UPDATE serialises them so at most one insert ever lands as
   * {@code ACTIVE}; the loser's insert fails the partial unique index outright rather than silently
   * producing two {@code ACTIVE} rows.
   *
   * <p>Publishes {@link KeyRotated} inside this same transaction (spec FS-2.3 D3) — {@code
   * status.worker}'s rotation handler consumes it to bump every one of this tenant's status lists'
   * version, forcing the existing periodic sweep to re-sign each with the new key within one cycle;
   * {@code tenant}'s own handler consumes the same event to keep {@code tenant.key_provider} in
   * sync (spec V3).
   *
   * <p>Stays on whichever provider the tenant's current {@code ACTIVE} key already uses (or the
   * platform default if the tenant somehow has none) — a plain rotate never changes provider by
   * itself. Use {@link #rotate(UUID, String, String)} to rotate onto a different provider
   * explicitly (spec D6: provider migration IS a normal rotation, nothing more).
   *
   * @param tenantId the tenant to rotate
   * @param tenantSlug the tenant's slug, used to build the new key's {@code kid}
   * @return the newly created, now-{@code ACTIVE} key's summary
   */
  @Transactional
  public IssuerKeySummary rotate(UUID tenantId, String tenantSlug) {
    String currentProvider =
        repository
            .findByTenantIdAndState(tenantId, STATE_ACTIVE)
            .map(IssuerKey::getProvider)
            .orElse(defaultProviderName);
    return rotate(tenantId, tenantSlug, currentProvider);
  }

  /**
   * Rotate the tenant's signing key onto a specific provider (spec FS-2.3 D5/D6) — the mechanism
   * behind both a same-provider routine rotation ({@link #rotate(UUID, String)} delegates here with
   * the current provider) and a SOFT→Vault (or any provider→provider) migration: D6's own wording
   * is that migration is nothing but a normal rotation whose new key happens to land on a different
   * backend. The retiring old key is untouched — it keeps whatever provider it was created with, so
   * a tenant's history can span multiple providers across its lifetime, each key routed correctly
   * by {@link #signWithActiveKey} / {@link #resolvePublicKey}.
   *
   * @param tenantId the tenant to rotate
   * @param tenantSlug the tenant's slug, used to build the new key's {@code kid}
   * @param requestedProvider the provider the new key should be created on
   * @return the newly created, now-{@code ACTIVE} key's summary
   * @throws ValidationException {@code KH-KEY-0400} if {@code requestedProvider} is not a
   *     registered provider
   */
  @Transactional
  public IssuerKeySummary rotate(UUID tenantId, String tenantSlug, String requestedProvider) {
    String oldKid =
        repository
            .findByTenantIdAndState(tenantId, STATE_ACTIVE)
            .map(IssuerKey::getKid)
            .orElse(null);
    repository.retireActive(tenantId, Instant.now());
    IssuerKey created = createActiveKey(tenantId, tenantSlug, requestedProvider);
    audit.record(AuditAction.KEY_ROTATED, "issuer_key", created.getKid(), null);
    eventPublisher.publishEvent(
        new KeyRotated(tenantId, oldKid, created.getKid(), created.getProvider(), Instant.now()));
    return toSummary(created);
  }

  /**
   * Retire a {@code RETIRING} key to {@code RETIRED} (spec FS-2.3 D4).
   *
   * <p>Only a {@code RETIRING} key may be retired — {@code ACTIVE} must be rotated out first,
   * {@code RETIRED} is already retired, and {@code PENDING} is never reachable today; any other
   * state throws {@link ConflictException} {@code KH-KEY-0409}. RLS scopes {@link
   * IssuerKeyRepository#findByKid} to the caller's own ambient tenant, so a {@code kid} belonging
   * to another tenant resolves identically to an unknown one — both throw {@link NotFoundException}
   * {@code KH-KEY-0404}.
   *
   * <p>The min-age guard ({@code khatm.keys.min-retiring-age}, default {@code P30D}) protects
   * long-lived offline documents whose verifier may not have refreshed its JWKS cache recently —
   * retiring a key too soon after rotation risks a document that was valid yesterday failing to
   * verify today for no reason the holder can explain. Measured from {@link IssuerKey#getValidTo()}
   * (set the moment this key left {@code ACTIVE}, i.e. exactly when it became {@code RETIRING}).
   * {@code force=true} bypasses the guard — always audited with {@code detail.forced=true} so a
   * deliberate early retirement is distinguishable from a routine one after the fact.
   *
   * <p>{@code RETIRED} keys stay published in JWKS and stay resolvable by {@link #resolvePublicKey}
   * — retiring never breaks verification of documents already signed with this key (spec FS-0.2
   * §3.2, FS-2.3 D4); only the deferred Phase-3 trust-bundle work will ever drop a key out of JWKS
   * entirely.
   *
   * @param kid the key to retire
   * @param force bypass the min-age guard (audited)
   * @return the now-{@code RETIRED} key's summary
   * @throws NotFoundException {@code KH-KEY-0404} if no such key exists for the current tenant
   * @throws ConflictException {@code KH-KEY-0409} if the key is not currently {@code RETIRING}
   * @throws ValidationException {@code KH-KEY-0422} if the key has not yet reached {@code
   *     khatm.keys.min-retiring-age} and {@code force} is {@code false}
   */
  @Transactional
  public IssuerKeySummary retire(String kid, boolean force) {
    IssuerKey key =
        repository
            .findByKid(kid)
            .orElseThrow(() -> new NotFoundException(ErrorCode.KH_KEY_0404, "key.not-found"));
    if (!STATE_RETIRING.equals(key.getState())) {
      throw new ConflictException(ErrorCode.KH_KEY_0409, "key.not-retiring");
    }
    Duration elapsed = Duration.between(key.getValidTo(), Instant.now());
    if (!force && elapsed.compareTo(minRetiringAge) < 0) {
      Duration remaining = minRetiringAge.minus(elapsed);
      throw new ValidationException(
          ErrorCode.KH_KEY_0422, "key.retiring-too-young", remaining.toString());
    }
    key.setState(STATE_RETIRED);
    repository.save(key);
    audit.record(AuditAction.KEY_RETIRED, "issuer_key", kid, Map.of("forced", force));
    return toSummary(key);
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
    KeyProvider provider = resolveProvider(active.getProvider());
    return provider.sign(active.getProviderRef(), active.getKid(), claims);
  }

  /**
   * Resolve the public key for a {@code kid}, strictly — no fallback to the current active key.
   *
   * <p>An unknown {@code kid} resolves to {@link Optional#empty()} (spec FS-0.5 §4, DoD #3). {@code
   * RETIRED} keys DO still resolve (spec FS-0.2 §3.2 / FS-2.3 D2/D4, corrected as of KH-2.3a —
   * earlier versions of this method and its {@code key.api.KeyVerifier} caller excluded {@code
   * RETIRED} here; that was a bug against the spec's own wording, not a deliberate design, fixed
   * this session): a document signed years ago with a since-retired key must keep verifying for as
   * long as that key stays in JWKS, which is the entire point of retiring rather than deleting.
   *
   * <p><b>Never calls a {@link KeyProvider} (KH-2.3b, spec FS-2.3 D5/D6):</b> before this session,
   * this method round-tripped to whichever provider generated the key on every single call —
   * meaning credential verification (spec's own "public artifacts don't need Vault at read time"
   * DoD claim, verified true only after this change) would have needed a Vault-backed tenant's
   * Vault reachable just to check a signature, and a killed Vault container would have broken
   * verify/consume, not just issuance. {@code issuer_key.public_jwk} already holds the complete
   * public JWK — written once at generation time (spec FS-0.2 §3.2), provider-agnostic, safe to
   * read directly. Parsing it here removes the round trip entirely, for every provider, not just
   * Vault: public-key resolution is now pure DB read, so JWKS publishing (already DB-only) and
   * signature verification are equally unaffected by any provider backend's availability.
   *
   * @param kid the key id to resolve
   * @return the public key handle, or empty only if {@code kid} is unknown
   */
  @Transactional(readOnly = true)
  Optional<PublicKeyHandle> resolvePublicKey(String kid) {
    return repository.findByKid(kid).map(KeyLifecycleService::toPublicKeyHandle);
  }

  private static PublicKeyHandle toPublicKeyHandle(IssuerKey key) {
    try {
      ECKey jwk = ECKey.parse(key.getPublicJwkJson());
      return new PublicKeyHandle(key.getKid(), key.getAlgo(), jwk.toECPublicKey());
    } catch (ParseException | JOSEException e) {
      // The public_jwk column is only ever written by this module's own KeyProvider#generate
      // implementations (never accepted from a request) — a parse failure here means the stored
      // row itself is corrupt, an internal invariant break, not a caller-facing input problem.
      throw new IllegalStateException("Corrupt public_jwk stored for kid=" + key.getKid(), e);
    }
  }

  /**
   * List the tenant's publicly publishable keys ({@code ACTIVE} + {@code RETIRING} + {@code
   * RETIRED}) for the legacy, default-tenant-only JWKS endpoint (spec FS-0.5 §6, {@code
   * key.web.JwksController}). {@code RETIRED} keys stay published (spec FS-0.2 §3.2 / FS-2.3 D4) —
   * only a Phase-3 trust-bundle mechanism will ever drop one out entirely.
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
                new IssuerKeyStatusView(
                    k.getKid(), k.getState(), k.getProvider(), k.getValidFrom(), k.getValidTo()))
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
   * List a tenant's publishable ({@code ACTIVE} + {@code RETIRING} + {@code RETIRED}) keys for
   * {@code tenant.web.TenantJwksController}'s per-tenant JWKS endpoint (spec FS-2.1 D8) — the same
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

  private IssuerKey createActiveKey(UUID tenantId, String tenantSlug, String providerName) {
    long seq = repository.countByTenantId(tenantId) + 1;
    String kid = tenantSlug + ":key-" + seq;
    KeyProvider provider = resolveProvider(providerName);
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
    return new IssuerKeySummary(
        key.getKid(), key.getState(), key.getProvider(), key.getValidFrom(), key.getValidTo());
  }
}
