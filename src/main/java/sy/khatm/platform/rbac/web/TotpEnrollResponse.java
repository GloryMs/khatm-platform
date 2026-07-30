package sy.khatm.platform.rbac.web;

/**
 * Response to {@code POST /api/v1/users/me/totp/enroll} (spec FS-2.2 V1) — the raw secret and
 * enrollment URI, shown exactly once (plaintext-once pattern).
 *
 * @param secretBase32 the raw secret, Base32-encoded, for manual entry if the QR code can't be
 *     scanned
 * @param otpAuthUri the standard {@code otpauth://totp/...} URI (QR-encodable)
 */
record TotpEnrollResponse(String secretBase32, String otpAuthUri) {}
