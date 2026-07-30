package sy.khatm.platform.rbac.web;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/v1/auth/login} request body (spec FS-0.6b DoD #1).
 *
 * @param username the submitted username
 * @param password the submitted plaintext password
 * @param tenantSlug the tenant to authenticate against (spec FS-2.2 — multi-tenant console login);
 *     omit or leave blank for the caller's ambient default tenant, preserving every pre-existing
 *     login call's exact behavior
 */
record LoginRequest(@NotBlank String username, @NotBlank String password, String tenantSlug) {}
