package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to verify a credential token.
 *
 * <p>{@code sdJwt} accepts the standard tilde-separated SD-JWT presentation ({@code
 * <jwt>~<d1>~..~<dn>~}), scanned from a QR code or NFC payload. A bare compact JWT with no tilde at
 * all is also accepted and treated as a <em>zero-disclosure presentation</em> — every claims_def
 * field is then subject to the mandatory-disclosure check (spec FS-0.4 §5); this is expected
 * behavior, not an error, unless {@code sd_fields} happens to cover every field.
 *
 * <p>An entirely blank/missing {@code sdJwt} is different from a zero-disclosure presentation — it
 * is not even an attempt at a credential, so it is rejected by Bean Validation (400) rather than
 * reaching {@code CredentialService#verify} as a domain result (spec FS-0.6a D1: "the request that
 * is structurally malformed... is the only thing that throws a validation error").
 *
 * @param sdJwt the SD-JWT presentation, or a bare compact JWT for a zero-disclosure presentation
 */
@Schema(name = "VerifyRequest", description = "Request to verify an SD-JWT credential presentation")
public record VerifyRequest(
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sdJwt) {}
