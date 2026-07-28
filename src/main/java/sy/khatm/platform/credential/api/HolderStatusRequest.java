package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to check a credential's current lifecycle status by proof of possession (spec FS-1.6 D3)
 * — a deliberate, explicit reversal of PR #33's original "no live uses-remaining channel" stance
 * (see {@code docs/STATE.md}'s KH-1.6-BE session entry for the recorded rationale).
 *
 * <p>{@code jwt} is the bare compact SD-JWT — the same string a stored credential's {@code
 * signed_payload} carries, with no disclosures attached (unlike {@link VerifyRequest#sdJwt}, which
 * also accepts a full tilde-separated presentation). Only signature possession is being proven
 * here; no claim content is ever read or returned by this endpoint (P1 rule).
 *
 * @param jwt the bare compact JWT
 */
@Schema(
    name = "HolderStatusRequest",
    description = "Request to check a credential's lifecycle status by proof of possession")
public record HolderStatusRequest(@NotBlank String jwt) {}
