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
 * @param mustChangePassword whether this user must set a real password before any other
 *     authenticated call succeeds (spec FS-2.2 D5) — {@code GET /api/v1/auth/me} is the one
 *     endpoint exempt from {@code rbac.security.PasswordChangeEnforcementFilter} specifically so a
 *     client can read this flag and route to the change screen, instead of only ever discovering
 *     the forced-change state via that filter's opaque {@code 403 KH-USR-0403}.
 */
public record UserView(
    String username,
    LocalizedText displayNameI18n,
    String preferredLang,
    boolean mustChangePassword) {}
