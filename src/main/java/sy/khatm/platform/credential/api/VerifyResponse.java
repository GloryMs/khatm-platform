package sy.khatm.platform.credential.api;

import java.util.Map;

/**
 * Result of an online credential verification.
 *
 * @param valid {@code true} if the signature is valid and the credential is not expired or revoked
 * @param reason machine-readable reason code (e.g. {@code valid}, {@code expired}, {@code revoked},
 *     {@code bad_signature})
 * @param claims JWT claims extracted from the token; present even when {@code valid} is false so
 *     the caller can display context
 * @param usesRemaining remaining consumption count from the database; {@code null} if the ref is
 *     unknown
 * @param revoked {@code true} if the credential has been explicitly revoked
 */
public record VerifyResponse(
    boolean valid,
    String reason,
    Map<String, Object> claims,
    Integer usesRemaining,
    boolean revoked) {}
