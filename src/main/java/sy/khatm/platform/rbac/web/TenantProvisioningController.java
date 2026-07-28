package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.rbac.domain.CreatedUser;
import sy.khatm.platform.rbac.domain.OnboardTenantResult;
import sy.khatm.platform.rbac.domain.TenantProvisioningService;
import sy.khatm.platform.shared.web.ErrorEnvelope;
import sy.khatm.platform.tenant.api.TenantView;

/**
 * The {@code platform:admin} cross-tenant provisioning endpoints under {@code
 * /api/v1/admin/tenants} (spec FS-2.2 D6): onboarding a tenant (with an optional first
 * administrator) and adding a user to an <em>existing</em> tenant. Both create {@code app_user}
 * rows, which is why this controller lives in {@code rbac.web} and not {@code tenant.web} — the
 * same Modulith-cycle avoidance that put {@code rbac.web.ConsumingPartyKeyController} (which mints
 * {@code api_key} rows) in {@code rbac.web}. The tenant-row + signing-key + status-list half of
 * onboarding is delegated to {@code tenant :: api}'s {@code TenantAdmin#create} (unchanged); the
 * list/get/suspend/activate tenant endpoints stay in {@code tenant.web.TenantAdminController}.
 *
 * <p>Thin: validate → call {@link TenantProvisioningService} → map.
 */
@RestController
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/admin/tenants")
@Tag(name = "tenants-admin", description = "Tenant administration — onboarding, listing, status")
class TenantProvisioningController {

  private final TenantProvisioningService provisioning;

  TenantProvisioningController(TenantProvisioningService provisioning) {
    this.provisioning = provisioning;
  }

  @Operation(
      summary = "Onboard a tenant",
      description =
          "Full onboarding: tenant row + first ACTIVE signing key + default status list (delegated"
              + " to tenant::api), the three-role catalog seeded, and — when initialAdmin is"
              + " present — the tenant's first TENANT_ADMIN with a one-time temporary password."
              + " Resumable: a retried slug fills whatever a prior partial onboarding left missing"
              + " (catalog and/or admin), never duplicates. Requires the platform:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Tenant onboarded (or resumed)"),
        @ApiResponse(
            responseCode = "400",
            description = "Bean Validation failed, or an invalid slug (KH-TNT-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the platform:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "A fully-onboarded tenant with this slug already exists (KH-TNT-0409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping
  OnboardTenantResponse onboard(@Valid @RequestBody OnboardTenantRequest req) {
    OnboardTenantRequest.InitialAdminRequest initial = req.initialAdmin();
    String initialUsername = initial == null ? null : initial.username();
    var initialName = initial == null ? null : initial.displayNameI18n().toLocalizedText();
    OnboardTenantResult result =
        provisioning.onboard(
            req.slug(),
            req.nameI18n().toLocalizedText(),
            req.type(),
            req.deployMode(),
            initialUsername,
            initialName);
    return toResponse(result);
  }

  @Operation(
      summary = "Add a user to an existing tenant",
      description =
          "Creates a user in a tenant other than the caller's own — the same creation shape as a"
              + " tenant admin's own-user create, run on behalf of the named tenant via"
              + " OnBehalfOfExecutor (audited ON_BEHALF_OF). Requires the platform:admin scope.",
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
            description = "Missing the platform:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "Target tenant not found (KH-TNT-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "Username already exists in that tenant (KH-USR-0409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/users")
  ResponseEntity<CreateUserResponse> createUserInTenant(
      @PathVariable UUID id, @Valid @RequestBody CreateUserRequest req) {
    CreatedUser created =
        provisioning.createUserInTenant(
            id, req.username(), req.displayNameI18n().toLocalizedText(), req.roles());
    return ResponseEntity.ok(
        new CreateUserResponse(created.id(), created.username(), created.temporaryPassword()));
  }

  private static OnboardTenantResponse toResponse(OnboardTenantResult result) {
    TenantView tenant = result.tenant();
    OnboardTenantResponse.InitialAdminResponse admin =
        result.initialAdminUsername() == null
            ? null
            : new OnboardTenantResponse.InitialAdminResponse(
                result.initialAdminUsername(), result.initialAdminTemporaryPassword());
    return new OnboardTenantResponse(
        tenant.id(),
        tenant.slug(),
        tenant.nameI18n(),
        tenant.type(),
        tenant.deployMode(),
        tenant.status(),
        tenant.createdAt(),
        admin);
  }
}
