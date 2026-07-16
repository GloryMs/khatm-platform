package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request to verify a credential token.
 *
 * <p>{@code sdJwt} accepts the standard tilde-separated SD-JWT presentation ({@code
 * <jwt>~<d1>~..~<dn>~}), scanned from a QR code or NFC payload. A bare compact JWT with no tilde at
 * all is also accepted and treated as a <em>zero-disclosure presentation</em> — every claims_def
 * field is then subject to the mandatory-disclosure check (spec FS-0.4 §5); this is expected
 * behavior, not an error, unless {@code sd_fields} happens to cover every field.
 *
 * @param sdJwt the SD-JWT presentation, or a bare compact JWT for a zero-disclosure presentation
 */
@Schema(name = "VerifyRequest", description = "Request to verify an SD-JWT credential presentation")
public record VerifyRequest(String sdJwt) {}
