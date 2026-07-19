package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to mint a fresh wallet claim code for an already-issued credential (KH-1.2.2, spec
 * FS-1.2.1 D2's re-issue recovery path).
 *
 * <p>The platform never persists a presentation's disclosures outside a {@code claim_code} row (P1)
 * — {@code sdJwt} must be the exact presentation string {@link IssueResponse#sdJwt} returned at
 * issuance, which the issuer is expected to retain for precisely this recovery case.
 *
 * @param sdJwt the full SD-JWT presentation covering this credential's disclosures — the same
 *     string {@code POST /issue} returned as {@code sdJwt}
 * @param ttlMinutes how long the new code remains claimable; {@code null} defaults to 15 minutes
 */
@Schema(
    name = "ClaimCodeMintRequest",
    description = "Request to mint a fresh wallet claim code for an already-issued credential")
public record ClaimCodeMintRequest(@NotBlank String sdJwt, Integer ttlMinutes) {}
