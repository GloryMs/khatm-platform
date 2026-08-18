package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to redeem a one-time wallet claim code (spec FS-1.2.1 D1).
 *
 * <p>The code is carried in the body only — never in the URL or a query parameter, so it never
 * lands in an access log line (SEC §9.7).
 *
 * @param code the raw one-time claim code, exactly as shown at issuance time
 */
@Schema(name = "ClaimRedeemRequest", description = "Request to redeem a one-time wallet claim code")
public record ClaimRedeemRequest(
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code) {}
