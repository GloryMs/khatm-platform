package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.rbac.api.CurrentActor;
import sy.khatm.platform.rbac.api.CurrentActorResolver;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.AuthService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;
import sy.khatm.platform.rbac.domain.LoginResult;
import sy.khatm.platform.rbac.domain.UserView;
import sy.khatm.platform.rbac.security.SessionAuthenticator;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Console session auth ({@code /api/v1/auth/*}) and admin API-key management ({@code
 * /api/v1/admin/api-keys/*}) (spec FS-0.6b §3).
 *
 * <p>Thin: validate → call the domain/security services → map. {@link SessionAuthenticator} owns
 * the actual {@code HttpSession} establishment/teardown so this class never touches Spring
 * Security's context machinery directly.
 *
 * <p>Module-private — Spring MVC discovers it via component scan; no other module references this
 * class.
 */
@RestController
@Tag(name = "auth", description = "Console session login/logout and admin API-key management")
class AuthController {

  private final AuthService authService;
  private final ApiKeyService apiKeyService;
  private final SessionAuthenticator sessionAuthenticator;
  private final CurrentActorResolver currentActorResolver;

  AuthController(
      AuthService authService,
      ApiKeyService apiKeyService,
      SessionAuthenticator sessionAuthenticator,
      CurrentActorResolver currentActorResolver) {
    this.authService = authService;
    this.apiKeyService = apiKeyService;
    this.sessionAuthenticator = sessionAuthenticator;
    this.currentActorResolver = currentActorResolver;
  }

  @Operation(
      summary = "Console login",
      description =
          "Authenticates a username/password pair for the current tenant and establishes a"
              + " server-side session (Redis-backed, cookie KHATM_SESSION). Every failure reason"
              + " — unknown user, wrong password, temporary lockout, administrative LOCKED/"
              + "DISABLED — returns the identical generic 401 (spec FS-0.6b D7); the real reason"
              + " is recorded only in the audit log.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Login succeeded; session cookie set"),
        @ApiResponse(
            responseCode = "401",
            description = "Authentication failed (KH-RBC-0401, generic message)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/auth/login")
  ResponseEntity<Void> login(
      @Valid @RequestBody LoginRequest req,
      HttpServletRequest request,
      HttpServletResponse response) {
    LoginResult result = authService.login(req.username(), req.password());
    sessionAuthenticator.establish(request, response, result);
    return ResponseEntity.ok().build();
  }

  @Operation(
      summary = "Console logout",
      description = "Invalidates the current session.",
      responses = {@ApiResponse(responseCode = "200", description = "Logged out")})
  @PostMapping("/api/v1/auth/logout")
  ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    sessionAuthenticator.clear(request, response);
    return ResponseEntity.ok().build();
  }

  @Operation(
      summary = "Current session's user",
      description =
          "Returns the authenticated user's username, display name, language, and scopes.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Current user"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/api/v1/auth/me")
  ResponseEntity<MeResponse> me() {
    CurrentActor actor =
        currentActorResolver
            .resolve()
            .orElseThrow(() -> new IllegalStateException("Authenticated request has no actor"));
    UserView view =
        authService
            .findUserView(actor.id())
            .orElseThrow(
                () -> new IllegalStateException("Authenticated user " + actor.id() + " not found"));
    return ResponseEntity.ok(
        new MeResponse(
            view.username(), view.displayNameI18n(), view.preferredLang(), actor.scopes()));
  }

  @Operation(
      summary = "Create an API key",
      description =
          "The response's rawKey is shown exactly once — the platform stores only its SHA-256"
              + " hash and prefix (spec FS-0.6b §4). tenantId (spec FS-2.1) defaults to the"
              + " caller's own tenant — a platform admin provisioning a newly onboarded tenant's"
              + " first key names it explicitly. Requires the admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Key created"),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "The named tenantId does not exist (KH-TNT-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/admin/api-keys")
  ResponseEntity<CreateApiKeyResponse> createApiKey(@Valid @RequestBody CreateApiKeyRequest req) {
    UUID targetTenant = req.tenantId() != null ? req.tenantId() : TenantContext.current();
    CreatedApiKey created =
        apiKeyService.create(req.ownerType(), req.ownerId(), req.scopes(), targetTenant);
    return ResponseEntity.ok(
        new CreateApiKeyResponse(created.id(), created.keyPrefix(), created.rawKey()));
  }

  @Operation(
      summary = "Revoke an API key",
      description =
          "The key stops authenticating on the very next request (spec FS-0.6b DoD #5)."
              + " Idempotent — revoking an already-revoked or unknown key still returns 200.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Revoked (or already inactive)"),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/admin/api-keys/{id}/revoke")
  ResponseEntity<Void> revokeApiKey(@PathVariable String id) {
    apiKeyService.revoke(UUID.fromString(id));
    return ResponseEntity.ok().build();
  }
}
