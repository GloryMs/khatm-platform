package sy.khatm.platform.credential.api;

/**
 * Request to verify a credential token.
 *
 * @param jwt the compact JWS string scanned from a QR code or NFC payload
 */
public record VerifyRequest(String jwt) {}
