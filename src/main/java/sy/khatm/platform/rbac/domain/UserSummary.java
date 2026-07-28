package sy.khatm.platform.rbac.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * A console user's admin view — the shape {@code GET /api/v1/users} and every {@code /api/v1/users
 * /{id}/...} action returns (spec FS-2.2 D5). Carries the role <em>codes</em> (from the fixed
 * seeded catalog), not the derived scopes — the catalog is closed and small, codes are what an
 * operator edits, and scopes are an authorization detail {@code rbac.security} resolves at login.
 *
 * <p>Public (unlike most of {@code rbac.domain}) because {@code rbac.web}'s controllers — a
 * different Java package inside the module-private {@code rbac} module — build their responses from
 * it. Same visibility precedent as {@link CreatedUser}/{@link CreatedApiKey}.
 *
 * @param id the user's id
 * @param username the user's username
 * @param displayNameI18n the user's bilingual display name
 * @param status {@code ACTIVE}, {@code LOCKED}, or {@code DISABLED}
 * @param roles the user's assigned role codes (from the fixed catalog)
 * @param createdAt when the user was created
 */
public record UserSummary(
    UUID id,
    String username,
    LocalizedText displayNameI18n,
    String status,
    List<String> roles,
    Instant createdAt) {}
