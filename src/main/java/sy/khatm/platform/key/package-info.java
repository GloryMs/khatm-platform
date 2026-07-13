/**
 * Key module — issuer key management and cryptographic signing.
 *
 * <p><b>Responsibilities:</b> hold the issuer key pair, sign credential JWTs (ES256), verify
 * signatures, expose the public JWKS and PEM endpoints so verifiers can cache the public key for
 * offline verification. Key material NEVER leaves this module (SEC §9, CLAUDE.md security
 * constants).
 *
 * <p><b>Exposed API:</b> {@link sy.khatm.platform.key.api.KeySigner} — the only surface other
 * modules may depend on.
 *
 * <p><b>Published events:</b> {@code KeyRotated} (future — KH-0.5)
 *
 * <p><b>Tables owned:</b> {@code signing_key} (future — KH-0.5; currently in-memory only)
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.key;
