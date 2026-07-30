package sy.khatm.platform.rbac.web;

/**
 * {@code POST /api/v1/auth/login} response body (spec FS-2.2 V1) — {@code null}/empty for the
 * classic no-2FA path (byte-for-byte the pre-TOTP behavior: {@code 200}, no body, session cookie
 * set), or {@code {"totpRequired": true, "challengeId": "..."}} with no cookie when the account has
 * an active TOTP enrollment. {@code challengeId} is submitted to {@code POST /api/v1/auth/totp}
 * along with a code or recovery code to complete the login.
 *
 * @param totpRequired always {@code true} when this body is present at all
 * @param challengeId the opaque, single-use challenge id
 */
record LoginChallengeResponse(boolean totpRequired, String challengeId) {}
