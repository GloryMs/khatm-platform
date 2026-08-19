package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.rbac.domain.CreatedUser;
import sy.khatm.platform.rbac.domain.OrgAdminService;
import sy.khatm.platform.rbac.domain.OrgReportView;
import sy.khatm.platform.rbac.domain.UserSummary;
import sy.khatm.platform.schema.api.SchemaSummary;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.shared.web.ErrorEnvelope;
import sy.khatm.platform.tenant.api.TenantRef;
import sy.khatm.platform.tenant.api.TenantView;

/**
 * The {@code org:admin} on-behalf-of plane (KH-2.6b, spec FS-2.5 §3/§4) under {@code
 * /api/v1/org/**} — deliberately its own, non-{@code /api/v1/admin/**} prefix (spec V2's default)
 * so a parent tenant's {@code org:admin} holder, who is not necessarily a {@code platform:admin} or
 * even a local {@code tenant:admin}, never has to reason about {@code /api/v1/admin/**}'s
 * platform-wide surface. Every route here requires {@code org:admin} ({@code
 * rbac.security.SecurityConfig}'s {@code ORG_PATH} rule) and acts only on the caller's own tenant's
 * <em>direct</em> children — {@code KH-ORG-0404} for anything else (a grandchild, an unrelated
 * tenant, a genuinely nonexistent id), per {@link OrgAdminService}'s own Javadoc.
 *
 * <p>Lives in {@code rbac.web} (not {@code tenant.web}), the same Modulith-cycle avoidance as
 * {@code TenantProvisioningController}. Thin: validate → call {@link OrgAdminService} → map.
 */
@RestController
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/org")
@Tag(
    name = "org-admin",
    description = "org:admin on-behalf-of plane — direct children only (spec FS-2.5 §3/§4)")
class OrgAdminController {

  private static final int DEFAULT_WINDOW_DAYS = 30;

  private final OrgAdminService orgAdmin;

  OrgAdminController(OrgAdminService orgAdmin) {
    this.orgAdmin = orgAdmin;
  }

  @Operation(
      summary = "List the caller's direct children",
      description =
          "Every direct child of the caller's own tenant and its status — never grandchildren"
              + " (spec §7). Requires the org:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "The caller's direct children"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the org:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/children")
  List<TenantRef> listChildren() {
    return orgAdmin.listChildren();
  }

  @Operation(
      summary = "List a direct child's users",
      description =
          "The same row shape GET /api/v1/users returns for a tenant admin's own tenant, run on"
              + " behalf of a direct child (audited ORG_ON_BEHALF_OF). Requires the org:admin"
              + " scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "The child's users"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the org:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "id is not a direct child of the caller's own tenant (KH-ORG-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/children/{id}/users")
  List<UserSummary> listChildUsers(@PathVariable UUID id) {
    return orgAdmin.listChildUsers(id);
  }

  @Operation(
      summary = "Create a user in a direct child",
      description =
          "Creates a user in a direct child with a generated temporary password (shown once) — the"
              + " same creation shape and constraints as a local tenant:admin's own-tenant create,"
              + " no additional privilege (spec §3). Audited ORG_ON_BEHALF_OF (parent) +"
              + " USER_CREATED (child). Requires the org:admin scope.",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "User created; temporary password returned once"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid username or role code (KH-USR-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the org:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "id is not a direct child of the caller's own tenant (KH-ORG-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "Username already exists in that child (KH-USR-0409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/children/{id}/users")
  ResponseEntity<CreateUserResponse> createChildUser(
      @PathVariable UUID id, @Valid @RequestBody CreateUserRequest req) {
    CreatedUser created =
        orgAdmin.createChildUser(
            id, req.username(), req.displayNameI18n().toLocalizedText(), req.roles());
    return ResponseEntity.ok(
        new CreateUserResponse(created.id(), created.username(), created.temporaryPassword()));
  }

  @Operation(
      summary = "Disable a user in a direct child",
      description =
          "Sets the child's user DISABLED — audited ORG_ON_BEHALF_OF (parent) + USER_DISABLED"
              + " (child). Requires the org:admin scope. Refused with 409 (KH-USR-0423) if this is"
              + " the child's last active administrator.",
      responses = {
        @ApiResponse(responseCode = "200", description = "User disabled"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the org:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description =
                "id is not a direct child of the caller's own tenant (KH-ORG-0404), or the user"
                    + " does not exist in it (KH-USR-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "Would remove the child's last active administrator (KH-USR-0423)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/children/{id}/users/{userId}/disable")
  UserSummary disableChildUser(@PathVariable UUID id, @PathVariable UUID userId) {
    return orgAdmin.disableChildUser(id, userId);
  }

  @Operation(
      summary = "Reset a user's password in a direct child",
      description =
          "Generates a new temporary password (shown once) for a user in a direct child — audited"
              + " ORG_ON_BEHALF_OF (parent) + USER_PASSWORD_RESET (child). Requires the org:admin"
              + " scope.",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Password reset; temporary password returned once"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the org:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description =
                "id is not a direct child of the caller's own tenant (KH-ORG-0404), or the user"
                    + " does not exist in it (KH-USR-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/children/{id}/users/{userId}/reset-password")
  ResponseEntity<CreateUserResponse> resetChildUserPassword(
      @PathVariable UUID id, @PathVariable UUID userId) {
    CreatedUser reset = orgAdmin.resetChildUserPassword(id, userId);
    return ResponseEntity.ok(
        new CreateUserResponse(reset.id(), reset.username(), reset.temporaryPassword()));
  }

  @Operation(
      summary = "List a direct child's schemas (read-only)",
      description =
          "Read-only — org:admin never manages a child's schemas, only views them (spec §3)."
              + " Audited ORG_ON_BEHALF_OF (parent) only, matching the platform-wide convention"
              + " that reads are not separately audited. Requires the org:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "The child's schemas"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the org:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "id is not a direct child of the caller's own tenant (KH-ORG-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/children/{id}/schemas")
  List<SchemaSummary> listChildSchemas(
      @PathVariable UUID id, @RequestParam(required = false) String status) {
    return orgAdmin.listChildSchemas(id, status);
  }

  @Operation(
      summary = "Suspend a direct child tenant",
      description =
          "Flips a direct child to SUSPENDED — tenant:admin degree, never a delete (spec §3)."
              + " Reuses TenantAdmin#suspend's existing no-cascade guard unchanged. Audited"
              + " ORG_ON_BEHALF_OF (parent) + TENANT_SUSPENDED (child). Requires the org:admin"
              + " scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Child suspended"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the org:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "id is not a direct child of the caller's own tenant (KH-ORG-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "The child itself has an active child of its own (KH-TNT-1409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/children/{id}/suspend")
  TenantView suspendChild(@PathVariable UUID id) {
    return orgAdmin.suspendChild(id);
  }

  @Operation(
      summary = "Reactivate a direct child tenant",
      description =
          "Flips a SUSPENDED direct child back to ACTIVE. Audited ORG_ON_BEHALF_OF (parent) +"
              + " TENANT_ACTIVATED (child). Requires the org:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Child activated"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the org:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "id is not a direct child of the caller's own tenant (KH-ORG-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/children/{id}/activate")
  TenantView activateChild(@PathVariable UUID id) {
    return orgAdmin.activateChild(id);
  }

  @Operation(
      summary = "Fetch the aggregated proofs-not-content report",
      description =
          "Issue/verify/consume/revoke counters per descendant tenant (any depth, transitive over"
              + " the full subtree — spec §7) plus a whole-subtree rollup, for the requested"
              + " window. Numbers only — never a row, a claim, or any other content (P1)."
              + " Defaults to the last 30 days when from/to are omitted. Audited"
              + " ORG_REPORT_VIEWED. Requires the org:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "The aggregated report"),
        @ApiResponse(
            responseCode = "400",
            description = "from/to was present but not a valid ISO-8601 instant",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the org:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/reports")
  OrgReportView reports(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    Instant toInstant = parseOrDefault(to, Instant.now());
    Instant fromInstant =
        parseOrDefault(from, toInstant.minus(DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS));
    return orgAdmin.report(fromInstant, toInstant);
  }

  private static Instant parseOrDefault(String raw, Instant fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new ValidationException(ErrorCode.KH_SYS_0400, "validation.failed");
    }
  }
}
