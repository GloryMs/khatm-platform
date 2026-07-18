package sy.khatm.platform.rbac.domain;

import sy.khatm.platform.shared.LocalizedText;

/**
 * A user's display details for {@code GET /api/v1/auth/me} (spec FS-0.6b DoD #1).
 *
 * <p>Public — see {@link LoginResult}'s Javadoc for why (same {@code domain} → {@code web}
 * cross-package visibility need within the module-private {@code rbac} module).
 *
 * @param username the user's username
 * @param displayNameI18n the user's bilingual display name
 * @param preferredLang the user's preferred UI language ({@code en} or {@code ar})
 */
public record UserView(String username, LocalizedText displayNameI18n, String preferredLang) {}
