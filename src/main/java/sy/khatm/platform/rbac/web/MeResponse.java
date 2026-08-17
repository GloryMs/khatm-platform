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
 * @param mustChangePassword whether this user must set a real password (via {@code POST
 *     /api/v1/users/me/password}) before any other authenticated call will succeed (spec FS-2.2 D5)
 *     — set on temporary-password provisioning (create/reset-password/onboarding's first admin),
 *     cleared on the first successful change. This endpoint is the one call exempt from {@code
 *     rbac.security.PasswordChangeEnforcementFilter}'s {@code 403 KH-USR-0403}, precisely so a
 *     client can read this flag here and route to the change screen proactively.
 * @param tenantSlug the caller's own tenant's slug (KH-2.4x, closing platform ask C8) — lets a
 *     client (e.g. the console's key-rotation confirm dialog) type-to-confirm against a
 *     human-legible tenant identifier instead of a signing key's opaque {@code kid}.
 * @param totpEnabled whether this user currently has an active (confirmed) TOTP enrollment
 *     (KH-2.4x, closing platform ask C7c) — a status flag only; whether TOTP is actually mandatory
 *     for this user depends on their scopes (see {@code
 *     rbac.security.TotpEnrollmentEnforcementFilter}), not encoded as a separate field here.
 */
record MeResponse(
    String username,
    LocalizedText displayNameI18n,
    String preferredLang,
    Set<String> scopes,
    boolean mustChangePassword,
    String tenantSlug,
    boolean totpEnabled) {}
