package sy.khatm.platform.rbac.web;

import java.util.UUID;

/**
 * {@code POST /api/admin/api-keys} response body (spec FS-0.6b §4, DoD #5).
 *
 * @param id the created key's id (needed to revoke it later)
 * @param keyPrefix the key's lookup prefix — safe to store/display going forward
 * @param rawKey the full {@code khk_<env>_<prefix>.<secret>} value — shown exactly once; the
 *     platform never stores or can re-display it
 */
record CreateApiKeyResponse(UUID id, String keyPrefix, String rawKey) {}
