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
 * <p>Thin: validate → call {@link UserAdminService} → map. Module-private — Spring MVC discovers it
 * via component scan; no other module references this class.
 */
@RestController
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "users", description = "Tenant user management (tenant:admin)")
class UserAdminController {

  private final UserAdminService userAdmin;
  private final CurrentActorResolver currentActorResolver;

  UserAdminController(UserAdminService userAdmin, CurrentActorResolver currentActorResolver) {
    this.userAdmin = userAdmin;
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
            description = "Missing the tenant:admin scope (KH-RBC-0403)",
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
            description = "Missing the tenant:admin scope (KH-RBC-0403)",
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
            description = "Missing the tenant:admin scope (KH-RBC-0403)",
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
            description = "Missing the tenant:admin scope (KH-RBC-0403)",
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
            description = "Missing the tenant:admin scope (KH-RBC-0403)",
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
            description = "Missing the tenant:admin scope (KH-RBC-0403)",
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
            description = "Missing the tenant:admin scope (KH-RBC-0403)",
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
    UUID userId =
        currentActorResolver
            .resolve()
            .orElseThrow(() -> new IllegalStateException("Authenticated request has no actor"))
            .id();
    userAdmin.changeOwnPassword(userId, req.currentPassword(), req.newPassword());
    return ResponseEntity.ok().build();
  }
}
