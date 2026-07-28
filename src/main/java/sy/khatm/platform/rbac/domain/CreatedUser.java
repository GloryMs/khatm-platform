package sy.khatm.platform.rbac.domain;

import java.util.UUID;

/**
 * The one-time result of {@link UserAdminService#create} / {@link UserAdminService#resetPassword} —
 * {@code temporaryPassword} is the only moment the secret ever exists outside the caller's own
 * storage; the platform stores only its argon2id hash and never logs or re-displays it (spec FS-2.2
 * D5/D6 — the identical plaintext-once pattern {@code ApiKeyService}'s {@link CreatedApiKey}
 * established for API keys, applied to a temporary human password instead of a bearer secret).
 *
 * @param id the created/reset user's id
 * @param username the user's username
 * @param temporaryPassword the generated temporary password — show this to the caller exactly once
 *     and never again; the user must change it at first login
 */
public record CreatedUser(UUID id, String username, String temporaryPassword) {}
