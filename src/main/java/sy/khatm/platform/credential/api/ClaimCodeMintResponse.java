package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Result of successfully minting a wallet claim code (KH-1.2.2). {@code code} is shown exactly once
 * — the platform stores only its SHA-256 hash from here on, the same one-time-delivery guarantee
 * {@code POST /api/v1/claims/redeem}'s response carries for the credential itself.
 *
 * @param code the raw one-time claim code — persist or display immediately, it cannot be retrieved
 *     again
 * @param expiresAt when the code stops being claimable
 */
@Schema(
    name = "ClaimCodeMintResponse",
    description = "Result of minting a one-time wallet claim code")
public record ClaimCodeMintResponse(String code, Instant expiresAt) {}
