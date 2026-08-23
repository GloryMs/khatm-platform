package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.rbac.api.CurrentActorResolver;
import sy.khatm.platform.rbac.domain.CreatedUser;
import sy.khatm.platform.rbac.domain.TotpEnrollment;
import sy.khatm.platform.rbac.domain.TotpService;
import sy.khatm.platform.rbac.domain.UserAdminService;
import sy.khatm.platform.rbac.domain.UserSummary;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Tenant user management (spec FS-2.2 D5) — {@code /api/v1/users/**}, gated {@code tenant:admin} (a
 * console session) and scoped to the caller's own tenant via {@code TenantContext} (RLS backstop).
 * The one exception is the self-service {@code POST /api/v1/users/me/password}, which any
 * authenticated console user may call (the forced-change gate routes temporary-password users here
 * and here alone).
 *
 * <p><b>Discoverability of the forced-change state:</b> every other operation here is subject to
 * {@code rbac.security.PasswordChangeEnforcementFilter} — a caller whose own {@code
 * mustChangePassword} flag is set gets {@code 403 KH-USR-0403} instead of this controller's normal
 * behavior, regardless of scope. A client should call {@code GET /api/v1/auth/me} (exempt from that
 * filter, carries {@code mustChangePassword}) before relying on any endpoint here, rather than only
 * discovering the state from a 403 on first use.
 *
 * <p>Thin: validate → call {@link UserAdminService} → map. Module-private — Spring MVC discovers it
 * via component scan; no other module references this class.
 */
@RestController
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "users", description = "Tenant user management (tenant:admin)")
class UserAdminController {

  private final UserAdminService userAdmin;
  private final TotpService totpService;
  private final CurrentActorResolver currentActorResolver;

  UserAdminController(
      UserAdminService userAdmin,
      TotpService totpService,
      CurrentActorResolver currentActorResolver) {
    this.userAdmin = userAdmin;
    this.totpService = totpService;
    this.currentActorResolver = currentActorResolver;
  }

  @Operation(
      summary = "List tenant users",
      description =
          "Every user of the caller's tenant, newest first. Requires the tenant:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "The tenant's users"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the tenant:admin scope (KH-RBC-0403), or the caller's own"
                    + " mustChangePassword flag is set (KH-USR-0403 — see GET /api/v1/auth/me)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/api/v1/users")
  List<UserSummary> list() {
    return userAdmin.list();
  }

  @Operation(
      summary = "Create a tenant user",
      description =
          "Creates a user with a generated temporary password (shown once) and forces a change at"
              + " first login. Roles are chosen from the fixed seeded catalog. Requires the"
              + " tenant:admin scope.",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "User created; temporary password returned once"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid username or role code (KH-USR-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the tenant:admin scope (KH-RBC-0403), the caller's own"
                    + " mustChangePassword flag is set (KH-USR-0403 — see GET /api/v1/auth/me), or"
                    + " a requested role exceeds the caller's own role-grant ceiling"
                    + " (KH-USR-2403 — only platform:admin may grant PLATFORM_ADMIN/ORG_ADMIN)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "Username already exists (KH-USR-0409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/users")
  ResponseEntity<CreateUserResponse> create(@Valid @RequestBody CreateUserRequest req) {
    CreatedUser created =
        userAdmin.create(req.username(), req.displayNameI18n().toLocalizedText(), req.roles());
    return ResponseEntity.ok(
        new CreateUserResponse(created.id(), created.username(), created.temporaryPassword()));
  }

  @Operation(
      summary = "Replace a user's roles",
      description =
          "Replaces the user's entire role set. Requires the tenant:admin scope. Refused with 409"
              + " (KH-USR-0423) if it would remove the tenant's last active administrator.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Roles replaced"),
        @ApiResponse(
            responseCode = "400",
            description = "Unknown role code (KH-USR-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the tenant:admin scope (KH-RBC-0403), the caller's own"
                    + " mustChangePassword flag is set (KH-USR-0403 — see GET /api/v1/auth/me), or"
                    + " a requested role exceeds the caller's own role-grant ceiling"
                    + " (KH-USR-2403 — only platform:admin may grant PLATFORM_ADMIN/ORG_ADMIN)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "User not found (KH-USR-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "Would remove the last active administrator (KH-USR-0423)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/users/{id}/roles")
  UserSummary replaceRoles(@PathVariable UUID id, @Valid @RequestBody ReplaceRolesRequest req) {
    return userAdmin.replaceRoles(id, req.roles());
  }

  @Operation(
      summary = "Lock a user",
      description =
          "Sets the user LOCKED. Requires the tenant:admin scope. Refused with 409 (KH-USR-0423) if"
              + " this is the tenant's last active administrator.",
      responses = {
        @ApiResponse(responseCode = "200", description = "User locked"),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the tenant:admin scope (KH-RBC-0403), or the caller's own"
                    + " mustChangePassword flag is set (KH-USR-0403 — see GET /api/v1/auth/me)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "User not found (KH-USR-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "Would remove the last active administrator (KH-USR-0423)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/users/{id}/lock")
  UserSummary lock(@PathVariable UUID id) {
    return userAdmin.lock(id);
  }

  @Operation(
      summary = "Unlock a user",
      description = "Restores a LOCKED/DISABLED user to ACTIVE. Requires the tenant:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "User unlocked"),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the tenant:admin scope (KH-RBC-0403), or the caller's own"
                    + " mustChangePassword flag is set (KH-USR-0403 — see GET /api/v1/auth/me)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "User not found (KH-USR-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/users/{id}/unlock")
  UserSummary unlock(@PathVariable UUID id) {
    return userAdmin.unlock(id);
  }

  @Operation(
      summary = "Disable a user",
      description =
          "Sets the user DISABLED. Requires the tenant:admin scope. Refused with 409 (KH-USR-0423)"
              + " if this is the tenant's last active administrator.",
      responses = {
        @ApiResponse(responseCode = "200", description = "User disabled"),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the tenant:admin scope (KH-RBC-0403), or the caller's own"
                    + " mustChangePassword flag is set (KH-USR-0403 — see GET /api/v1/auth/me)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "User not found (KH-USR-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "Would remove the last active administrator (KH-USR-0423)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/users/{id}/disable")
  UserSummary disable(@PathVariable UUID id) {
    return userAdmin.disable(id);
  }

  @Operation(
      summary = "Reset a user's password",
      description =
          "Generates a new temporary password (shown once) and forces a change at next login."
              + " Requires the tenant:admin scope.",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Password reset; temporary password returned once"),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the tenant:admin scope (KH-RBC-0403), or the caller's own"
                    + " mustChangePassword flag is set (KH-USR-0403 — see GET /api/v1/auth/me)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "User not found (KH-USR-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/users/{id}/reset-password")
  ResponseEntity<CreateUserResponse> resetPassword(@PathVariable UUID id) {
    CreatedUser reset = userAdmin.resetPassword(id);
    return ResponseEntity.ok(
        new CreateUserResponse(reset.id(), reset.username(), reset.temporaryPassword()));
  }

  @Operation(
      summary = "Change own password",
      description =
          "The self-service password change — the one call a temporary-password user may make while"
              + " must_change_password is set, and the call that clears it. Any authenticated"
              + " console user may call it; the target user is taken from the session principal,"
              + " never the body.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Password changed"),
        @ApiResponse(
            responseCode = "400",
            description = "Current password incorrect (KH-USR-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "No valid session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/users/me/password")
  ResponseEntity<Void> changeMyPassword(@Valid @RequestBody ChangePasswordRequest req) {
    UUID userId = currentUserId();
    userAdmin.changeOwnPassword(userId, req.currentPassword(), req.newPassword());
    return ResponseEntity.ok().build();
  }

  @Operation(
      summary = "Begin TOTP enrollment",
      description =
          "Generates a fresh secret (encrypted at rest) and returns it, Base32-encoded, plus an"
              + " otpauth:// enrollment URI — shown exactly once. Any authenticated console session"
              + " may enroll its own user. Refused with 409 if TOTP is already active (an"
              + " administrator must reset it first); calling this again before confirming simply"
              + " supersedes the previous, not-yet-confirmed secret.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Enrollment secret and URI, shown once"),
        @ApiResponse(
            responseCode = "403",
            description = "No valid session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "TOTP is already active for this user (KH-USR-1409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/users/me/totp/enroll")
  ResponseEntity<TotpEnrollResponse> enrollTotp() {
    UUID userId = currentUserId();
    TotpEnrollment enrollment = totpService.enroll(userId);
    return ResponseEntity.ok(
        new TotpEnrollResponse(enrollment.secretBase32(), enrollment.otpAuthUri()));
  }

  @Operation(
      summary = "Confirm TOTP enrollment",
      description =
          "Activates a pending enrollment with a live code from the authenticator app and returns"
              + " 10 one-time recovery codes — shown exactly once. Refused with 409 if there is no"
              + " pending enrollment, it is already active, or it has expired (re-enroll in any of"
              + " those cases); refused with 400 if the code does not match.",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "TOTP activated; recovery codes shown once"),
        @ApiResponse(
            responseCode = "400",
            description = "The code does not match (KH-USR-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "No valid session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description =
                "No pending enrollment, already active, or the pending enrollment expired"
                    + " (KH-USR-1409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/users/me/totp/confirm")
  ResponseEntity<TotpConfirmResponse> confirmTotp(@Valid @RequestBody TotpConfirmRequest req) {
    UUID userId = currentUserId();
    List<String> recoveryCodes = totpService.confirm(userId, req.code());
    return ResponseEntity.ok(new TotpConfirmResponse(recoveryCodes));
  }

  @Operation(
      summary = "Reset a user's TOTP enrollment",
      description =
          "Clears the target user's TOTP enrollment and invalidates their remaining recovery"
              + " codes — they re-enroll at next login if a mandatory scope requires it. Idempotent"
              + " (a user with no TOTP enrolled is a no-op). Requires the tenant:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "TOTP reset (or already inactive)"),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the tenant:admin scope (KH-RBC-0403), or the caller's own"
                    + " mustChangePassword flag is set (KH-USR-0403 — see GET /api/v1/auth/me)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "User not found (KH-USR-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/users/{id}/totp/reset")
  ResponseEntity<Void> resetTotp(@PathVariable UUID id) {
    totpService.resetForUserInCurrentTenant(id);
    return ResponseEntity.ok().build();
  }

  private UUID currentUserId() {
    return currentActorResolver
        .resolve()
        .orElseThrow(() -> new IllegalStateException("Authenticated request has no actor"))
        .id();
  }
}
