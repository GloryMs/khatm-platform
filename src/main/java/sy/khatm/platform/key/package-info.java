/**
 * Key module — issuer key management and cryptographic signing.
 *
 * <p><b>Responsibilities:</b> hold issuer signing keys (ES256, one {@code ACTIVE} per tenant), sign
 * credential JWTs, resolve public keys strictly by {@code kid} for verification, and expose the
 * public JWKS endpoint so verifiers can cache public keys for offline verification. Key material
 * NEVER leaves this module (SEC §9, CLAUDE.md security constants) — persisted only inside a
 * provider-managed keystore ({@link sy.khatm.platform.key.domain.SoftKeyProvider}'s PKCS#12 file
 * today; KMS/HSM later, KH-2.3/KH-3.1), never in the database, logs, or an API response.
 *
 * <p><b>Exposed API:</b> {@link sy.khatm.platform.key.api.KeySigner} and {@link
 * sy.khatm.platform.key.api.KeyVerifier} ("sign this" / "verify this"), plus two deliberately
 * narrow KH-2.1 additions (spec FS-2.1 D6/D8): {@link
 * sy.khatm.platform.key.api.TenantKeyProvisioner} (idempotent first-key provisioning for the
 * tenant-onboarding admin plane) and {@link sy.khatm.platform.key.api.JwksLookup} (a tenant's
 * publishable JWKS material, called from {@code tenant.web.TenantJwksController} rather than this
 * module's own {@code web} — a reverse {@code key → tenant :: api} dependency to resolve a slug
 * would be a Modulith cycle, since {@code tenant} already depends on this module for onboarding).
 * The full key-management SPI ({@code KeyProvider}, with {@code rotate()}) stays module-private
 * (spec FS-0.5 §2, D1): other modules still never see general rotation, only these specific, narrow
 * operations.
 *
 * <p><b>Published events:</b> {@link sy.khatm.platform.key.events.KeyRotated} (KH-2.3a, spec FS-2.3
 * D3) — fired inside {@code KeyLifecycleService#rotate}'s transaction, externalized to the {@code
 * khatm.credential.events} stream (ADR-09). Consumed by {@code status.worker}'s rotation handler,
 * which bumps every one of the rotated tenant's status lists' version so the periodic sweep
 * re-signs them with the new key. {@code key} itself has no dependency on {@code status} — only
 * {@code status} (which already depends on {@code key :: api}) knows this event exists.
 *
 * <p><b>Tables owned:</b> {@code issuer_key} (table exists since the KH-0.2.1 baseline schema;
 * populated and rotated as of KH-0.5 by {@link sy.khatm.platform.key.domain.KeyLifecycleService}).
 *
 * <p><b>Signing-key status (KH-1.1.5-BE, spec FS-1.5.4 #4):</b> {@code
 * web.SigningKeyStatusController} serves {@code GET /api/v1/admin/signing-keys} — every key
 * regardless of state (including {@code RETIRED}), lifecycle fields only, never JWK material — via
 * {@link sy.khatm.platform.key.domain.KeyLifecycleService#listAllStatuses}. Entirely inside this
 * module, reading this module's own data; no new {@code key :: api} surface was added (a deliberate
 * scope cut — see {@code docs/STATE.md} — so the "other modules must never see rotation" stance
 * above stays untouched).
 *
 * <p><b>Rotation/retirement (KH-2.3a, spec FS-2.3 D2/D4):</b> {@code
 * web.SigningKeyRotationController} serves {@code POST /api/v1/admin/signing-keys/rotate} and
 * {@code POST /api/v1/admin/signing-keys/{kid}/retire}, both {@code key:manage}, both acting only
 * on the caller's own ambient tenant — no cross-tenant path exists for either, so neither needed
 * {@code shared.OnBehalfOfExecutor}. {@code RETIRED} keys stay published in JWKS and stay
 * resolvable by {@code KeyVerifier} (spec FS-0.2 §3.2 / FS-2.3 D2/D4 — corrected this session;
 * earlier code excluded {@code RETIRED} from both, which was a bug against the spec, not a
 * deliberate design).
 *
 * <p><b>Multi-provider / Vault Transit (KH-2.3b, spec FS-2.3 D5/D6):</b> {@link
 * sy.khatm.platform.key.domain.VaultTransitProvider} is a second {@link
 * sy.khatm.platform.key.domain.KeyProvider} implementation, registered alongside {@code
 * SoftKeyProvider} (not swapped in its place — spec D3's original "one provider, config-selected"
 * shape is superseded by "every configured provider registered, resolved per key," spec V3's
 * per-tenant column) whenever {@code khatm.keys.vault.enabled=true}. Signing keys never leave Vault
 * ({@code exportable=false}); every signature comes back through Vault's own {@code transit/sign}
 * endpoint. Provider migration (SOFT→Vault or any provider→provider) is not a special operation —
 * it is {@code POST /rotate} with an explicit {@code provider} field, spec D6's "migration is
 * nothing but a normal rotation." A connectivity failure to a KMS-class provider at sign/create
 * time fails closed ({@code KH-KEY-0503}) — never a silent fallback to SOFT, which would be a
 * key-security downgrade. {@code tenant.key_provider} (new column, veto V3) tracks each tenant's
 * current provider, kept in sync by {@code tenant.worker.TenantKeyProviderSyncHandler} consuming
 * {@link sy.khatm.platform.key.events.KeyRotated}'s new {@code provider} field — {@code key} itself
 * never writes to the {@code tenant} table.
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.key;
