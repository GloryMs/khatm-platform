package sy.khatm.platform.rbac.web;

import java.util.List;

/**
 * Response to {@code POST /api/v1/users/me/totp/confirm} (spec FS-2.2 V1) — 10 one-time recovery
 * codes, shown exactly once (plaintext-once pattern); only their hash is ever persisted.
 *
 * @param recoveryCodes the 10 plaintext-once recovery codes
 */
record TotpConfirmResponse(List<String> recoveryCodes) {}
