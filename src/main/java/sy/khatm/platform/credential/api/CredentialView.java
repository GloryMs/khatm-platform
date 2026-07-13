package sy.khatm.platform.credential.api;

import java.time.Instant;

/**
 * Read-only view of a credential — returned by lookup endpoints.
 *
 * <p>Contains no PII and no key material; the JWT is included so callers can re-display or
 * re-encode it into a QR code.
 *
 * @param id internal UUID (opaque)
 * @param ref human-readable reference
 * @param schemaCode credential type code
 * @param usesRemaining remaining consumption count
 * @param maxUses maximum allowed consumptions
 * @param revoked whether this credential has been revoked
 * @param validTo expiry timestamp
 * @param jwt compact JWS string
 */
public record CredentialView(
    String id,
    String ref,
    String schemaCode,
    int usesRemaining,
    int maxUses,
    boolean revoked,
    Instant validTo,
    String jwt) {}
