package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/v1/users/me/totp/confirm} (spec FS-2.2 V1).
 *
 * @param code the 6-digit code from the authenticator app
 */
record TotpConfirmRequest(
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code) {}
