package sy.khatm.platform.rbac.web;

import java.util.UUID;

/**
 * The response to {@code POST /api/v1/users} and {@code POST /api/v1/users/{id}/reset-password}
 * (and {@code POST /api/v1/admin/tenants/{id}/users}) — carries the one-time temporary password,
 * shown exactly once (spec FS-2.2 D5/D6, the plaintext-once pattern).
 *
 * @param id the created/reset user's id
 * @param username the user's username
 * @param temporaryPassword the generated temporary password — shown exactly once; the platform
 *     stores only its argon2id hash and can never re-display it
 */
record CreateUserResponse(UUID id, String username, String temporaryPassword) {}
