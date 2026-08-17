package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/v1/auth/totp} request body (spec FS-2.2 V1) — completes a login challenge issued
 * by {@code POST /api/v1/auth/login}. Exactly one of {@code code}/{@code recoveryCode} must be
 * provided (validated by {@code rbac.domain.AuthService}, not Bean Validation, since a shape-only
 * 400 here still doesn't need to reveal anything about a specific account).
 *
 * @param challengeId the id returned by the login response
 * @param code a live TOTP code from the authenticator app, or {@code null} if submitting a recovery
 *     code instead
 * @param recoveryCode a one-time recovery code, or {@code null} if submitting a TOTP code instead
 */
record TotpChallengeRequest(
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String challengeId,
    String code,
    String recoveryCode) {}
