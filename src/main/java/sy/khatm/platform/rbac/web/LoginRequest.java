package sy.khatm.platform.rbac.web;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/v1/auth/login} request body (spec FS-0.6b DoD #1). */
record LoginRequest(@NotBlank String username, @NotBlank String password) {}
