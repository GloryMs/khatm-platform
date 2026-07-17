package sy.khatm.platform.rbac.domain;

import java.util.Set;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * The outcome of a successful {@link AuthService#login}.
 *
 * <p>Public (unlike most of {@code rbac.domain}) because {@code rbac.web.AuthController} — a
 * different Java package, even though both are inside the module-private {@code rbac} module —
 * needs it to build the session's {@code Authentication} and the {@code /api/auth/me} response
 * (same visibility precedent as {@code key.domain.KeyLifecycleService}, spec FS-0.5 D-notes).
 *
 * @param userId the authenticated user's id
 * @param username the authenticated user's username
 * @param displayNameI18n the user's bilingual display name
 * @param preferredLang the user's preferred UI language ({@code en} or {@code ar})
 * @param scopes the union of every scope granted by the user's assigned roles
 */
public record LoginResult(
    UUID userId,
    String username,
    LocalizedText displayNameI18n,
    String preferredLang,
    Set<String> scopes) {}
