package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/users/me/password} request body (spec FS-2.2 D5) — the one call allowed while
 * {@code must_change_password} is set, and the call that clears it.
 *
 * @param currentPassword the user's current password, verified before anything is changed
 * @param newPassword the new password (8–128 characters)
 */
record ChangePasswordRequest(
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currentPassword,
    @NotBlank @Size(min = 8, max = 128) @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String newPassword) {}
