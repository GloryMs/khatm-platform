package sy.khatm.platform.rbac.domain;

/**
 * Result of {@link TotpService#enroll} — plaintext-once, like {@link CreatedUser}'s temporary
 * password: the raw secret is never retrievable again after this response.
 *
 * @param secretBase32 the raw secret, Base32-encoded, for manual entry if the QR code can't be
 *     scanned
 * @param otpAuthUri the standard {@code otpauth://totp/...} enrollment URI (QR-encodable)
 */
public record TotpEnrollment(String secretBase32, String otpAuthUri) {}
