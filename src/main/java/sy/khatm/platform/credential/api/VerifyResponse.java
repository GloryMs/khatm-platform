package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Result of an online credential verification.
 *
 * <p>A verification failure is a domain result, not an API error (spec FS-0.6a D1) — this response
 * is always HTTP 200 for a well-formed request; it is never wrapped in the error envelope. {@code
 * reason} is one of {@code sy.khatm.platform.shared.error.VerifyReason}'s codes (module-private to
 * {@code credential}'s implementation — the wire value is just the string).
 *
 * @param valid {@code true} if the signature is valid and the credential is not expired or revoked
 * @param reason machine-readable reason code (e.g. {@code valid}, {@code expired}, {@code revoked},
 *     {@code bad_signature}, {@code unknown_kid}, {@code forged_disclosure}, {@code
 *     duplicate_disclosure}, {@code withheld_mandatory_claim}, {@code bad_sd_alg}, {@code
 *     malformed})
 * @param reasonMessage {@code reason} resolved to a localized, human-readable sentence for the
 *     request's locale (spec FS-0.6a §3) — clients that don't want to maintain their own copy of
 *     the reason vocabulary can display this directly
 * @param claims the <em>decoded</em> result: SD-JWT structural fields (spec FS-0.4 D3) plus only
 *     the claim name/value pairs whose disclosures were actually presented and validated — never
 *     the raw {@code _sd} digest list. Present even when {@code valid} is false so the caller can
 *     display context.
 * @param usesRemaining remaining consumption count from the database; {@code null} if the ref is
 *     unknown
 * @param revoked {@code true} if the credential has been explicitly revoked
 */
@Schema(
    name = "VerifyResponse",
    description = "Result of an SD-JWT credential presentation verification")
public record VerifyResponse(
    boolean valid,
    String reason,
    String reasonMessage,
    Map<String, Object> claims,
    Integer usesRemaining,
    boolean revoked) {}
