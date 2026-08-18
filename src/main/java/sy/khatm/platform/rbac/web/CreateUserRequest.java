package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

/**
 * {@code POST /api/v1/users} (and {@code POST /api/v1/admin/tenants/{id}/users}) request body (spec
 * FS-2.2 D5/D6).
 *
 * @param username a valid username slug ({@code ^[a-z0-9][a-z0-9._-]{2,63}$}); the format itself is
 *     validated by the service ({@code KH-USR-0400}), not Bean Validation
 * @param displayNameI18n bilingual display name; both {@code en} and {@code ar} required
 * @param roles role codes from the fixed seeded catalog; may be omitted/empty for a login-only user
 */
record CreateUserRequest(
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,
    @NotNull @Valid DisplayNameI18nRequest displayNameI18n,
    Set<String> roles) {}
