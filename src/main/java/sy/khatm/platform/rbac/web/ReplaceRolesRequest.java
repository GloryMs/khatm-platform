package sy.khatm.platform.rbac.web;

import java.util.Set;

/**
 * {@code POST /api/v1/users/{id}/roles} request body (spec FS-2.2 D5) — the new role set that
 * replaces the user's current roles entirely. Codes must be from the fixed seeded catalog; may be
 * empty to remove all roles (a login-only user).
 *
 * @param roles the new role codes
 */
record ReplaceRolesRequest(Set<String> roles) {}
