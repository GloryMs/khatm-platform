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
 * sy.khatm.platform.key.api.KeyVerifier} — the only surface other modules may depend on. The full
 * key-management SPI ({@code KeyProvider}, with {@code rotate()}) is module-private on purpose
 * (spec FS-0.5 §2, D1): other modules must never see rotation, only "sign this" / "verify this."
 *
 * <p><b>Published events:</b> none yet ({@code KeyRotated} — future, once rotation gets an
 * admin-triggered path in KH-2.2).
 *
 * <p><b>Tables owned:</b> {@code issuer_key} (table exists since the KH-0.2.1 baseline schema;
 * populated and rotated as of KH-0.5 by {@link sy.khatm.platform.key.domain.KeyLifecycleService}).
 *
 * <p><b>Signing-key status (KH-1.1.5-BE, spec FS-1.5.4 #4):</b> {@code
 * web.SigningKeyStatusController} serves {@code GET /api/v1/admin/signing-keys} — every key
 * regardless of state (including {@code RETIRED}), lifecycle fields only, never JWK material — via
 * {@link sy.khatm.platform.key.domain.KeyLifecycleService#listAllStatuses} (new). Entirely inside
 * this module, reading this module's own data; no new {@code key :: api} surface was added (a
 * deliberate scope cut this session — see {@code docs/STATE.md} — so the "other modules must never
 * see rotation" stance above stays untouched for now).
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.key;
