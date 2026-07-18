package sy.khatm.platform.rbac.web;

import java.util.Set;
import sy.khatm.platform.shared.LocalizedText;

/**
 * {@code GET /api/v1/auth/me} response body (spec FS-0.6b DoD #1).
 *
 * @param username the authenticated user's username
 * @param displayNameI18n the user's bilingual display name
 * @param preferredLang the user's preferred UI language ({@code en} or {@code ar})
 * @param scopes the user's effective scopes (their roles' union)
 */
record MeResponse(
    String username, LocalizedText displayNameI18n, String preferredLang, Set<String> scopes) {}
