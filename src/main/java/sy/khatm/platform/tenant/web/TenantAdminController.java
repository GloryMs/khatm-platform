package sy.khatm.platform.tenant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.shared.web.ErrorEnvelope;
import sy.khatm.platform.tenant.api.TenantAdmin;
import sy.khatm.platform.tenant.api.TenantView;

/**
 * Tenant admin/onboarding plane (spec FS-2.1 D6, {@code /api/v1/admin/tenants}) — register new
 * tenants (full onboarding: tenant row + first {@code ACTIVE} signing key + default status list),
 * list every tenant platform-wide, and flip a tenant's {@code ACTIVE}/{@code SUSPENDED} status.
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
      summary = "Onboard a tenant",
      description =
          "Full onboarding: creates the tenant row, provisions its first ACTIVE signing key, and"
              + " creates its default status list (<slug>-<year>, capacity 131072) before this call"
              + " returns. Calling this again with a slug that already has a fully-onboarded tenant"
              + " is a conflict (KH-TNT-0409); calling it again with a slug whose onboarding"
              + " previously died partway through resumes it instead of conflicting. Requires the"
              + " platform:admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Tenant onboarded (or resumed)"),
        @ApiResponse(
            responseCode = "400",
            description = "Bean Validation failed, or an invalid slug format (KH-TNT-0400)",
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
  TenantView create(@Valid @RequestBody CreateTenantRequest req) {
    return admin.create(req.slug(), req.nameI18n().toLocalizedText(), req.type(), req.deployMode());
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
