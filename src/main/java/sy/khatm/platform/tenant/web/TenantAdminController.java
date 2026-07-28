package sy.khatm.platform.tenant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.shared.web.ErrorEnvelope;
import sy.khatm.platform.tenant.api.TenantAdmin;
import sy.khatm.platform.tenant.api.TenantView;

/**
 * Tenant admin plane (spec FS-2.1 D6, {@code /api/v1/admin/tenants}) — list every tenant
 * platform-wide, fetch one, and flip a tenant's {@code ACTIVE}/{@code SUSPENDED} status.
 *
 * <p><b>KH-2.2b — onboarding create relocated:</b> {@code POST /api/v1/admin/tenants} (onboard a
 * tenant, now optionally with its first administrator, spec FS-2.2 D6) moved to {@code
 * rbac.web.TenantProvisioningController}. The onboarding flow creates an {@code app_user} (the
 * first admin) and seeds the {@code rbac}-owned role catalog, so it lives in {@code rbac.web} for
 * the same Modulith-cycle avoidance that put {@code rbac.web.ConsumingPartyKeyController} there —
 * {@code tenant → rbac} would cycle against the existing {@code rbac → tenant :: api} edge. The
 * URL, the {@code platform:admin} gate, and {@code TenantAdmin#create}'s resumable-onboarding
 * semantics are all unchanged; only the handling bean moved modules.
 *
 * <p>Guarded by the {@code platform:admin} scope exclusively (spec FS-2.2 D2) — {@code
 * rbac.security.SecurityConfig}'s {@code ADMIN_TENANTS_PATH} rule, {@code
 * ScopeGuard.requireScope(ScopeRegistry.PLATFORM_ADMIN)}. This is the one cross-tenant plane on the
 * platform; no other scope grants access here, not even {@code tenant:admin}.
 *
 * <p>Thin: validate → call {@link TenantAdmin} → map. Module-private — Spring MVC discovers it via
 * component scan; no other module references this class.
 */
@RestController
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/admin/tenants")
@Tag(name = "tenants-admin", description = "Tenant administration — onboarding, listing, status")
class TenantAdminController {

  private final TenantAdmin admin;

  TenantAdminController(TenantAdmin admin) {
    this.admin = admin;
  }

  @Operation(
      summary = "List tenants",
      description =
          "Every tenant registered on the platform (newest first). Requires the admin" + " scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "The platform's tenants"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the platform:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping
  List<TenantView> list() {
    return admin.list();
  }

  @Operation(
      summary = "Fetch a tenant",
      description = "One tenant by id. Requires the platform:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "The tenant"),
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
            description = "No tenant with this id (KH-TNT-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/{id}")
  TenantView get(@PathVariable String id) {
    return admin.get(UUID.fromString(id));
  }

  @Operation(
      summary = "Suspend a tenant",
      description =
          "Flips the tenant to SUSPENDED — its own users' sessions and API keys immediately stop"
              + " authenticating (spec D7), the same outcome as a revoked key. Already-issued"
              + " credentials keep verifying/consuming, and the tenant's JWKS + status lists stay"
              + " public (spec V4) — suspension blocks new issuance only. Idempotent. Requires the"
              + " platform:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Tenant suspended"),
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
            description = "No tenant with this id (KH-TNT-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/suspend")
  TenantView suspend(@PathVariable String id) {
    return admin.suspend(UUID.fromString(id));
  }

  @Operation(
      summary = "Reactivate a tenant",
      description =
          "Flips a SUSPENDED tenant back to ACTIVE — its users'/API keys' authentication resumes."
              + " Idempotent. Requires the platform:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Tenant activated"),
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
            description = "No tenant with this id (KH-TNT-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/activate")
  TenantView activate(@PathVariable String id) {
    return admin.activate(UUID.fromString(id));
  }
}
