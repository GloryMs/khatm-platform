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
import sy.khatm.platform.rbac.domain.LoginOutcome;
import sy.khatm.platform.rbac.domain.LoginResult;
import sy.khatm.platform.rbac.domain.UserView;
import sy.khatm.platform.rbac.security.SessionAuthenticator;
import sy.khatm.platform.shared.OnBehalfOfExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.web.ErrorEnvelope;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

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
  private final TenantDirectory tenantDirectory;
  private final OnBehalfOfExecutor onBehalfOf;

  AuthController(
      AuthService authService,
      ApiKeyService apiKeyService,
      SessionAuthenticator sessionAuthenticator,
      CurrentActorResolver currentActorResolver,
      TenantDirectory tenantDirectory,
      OnBehalfOfExecutor onBehalfOf) {
    this.authService = authService;
    this.apiKeyService = apiKeyService;
    this.sessionAuthenticator = sessionAuthenticator;
    this.currentActorResolver = currentActorResolver;
    this.tenantDirectory = tenantDirectory;
    this.onBehalfOf = onBehalfOf;
  }

  @Operation(
      summary = "Console login",
      description =
          "Authenticates a username/password pair and establishes a server-side session"
              + " (Redis-backed, cookie KHATM_SESSION). The optional tenantSlug (spec FS-2.2)"
              + " authenticates against that tenant specifically; omit or leave it blank to log"
              + " into the caller's ambient default tenant, unchanged from before. Every failure"
              + " reason — unknown user, wrong password, temporary lockout, administrative LOCKED/"
              + "DISABLED, or an unknown/SUSPENDED tenantSlug — returns the identical generic 401"
              + " (spec FS-0.6b D7); the real reason is recorded only in the audit log. If the"
              + " account has an active TOTP enrollment (spec FS-2.2 V1), no session is created yet"
              + " — the response body instead carries totpRequired:true and a challengeId to submit"
              + " to POST /api/v1/auth/totp.",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description =
                "Login succeeded (session cookie set, empty body), or a TOTP challenge was issued"
                    + " (no cookie, body carries totpRequired:true + challengeId)"),
        @ApiResponse(
            responseCode = "401",
            description = "Authentication failed (KH-RBC-0401, generic message)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/auth/login")
  ResponseEntity<LoginChallengeResponse> login(
      @Valid @RequestBody LoginRequest req,
      HttpServletRequest request,
      HttpServletResponse response) {
    LoginOutcome outcome = authService.login(req.username(), req.password(), req.tenantSlug());
    return switch (outcome) {
      case LoginOutcome.Success success -> {
        sessionAuthenticator.establish(request, response, success.result());
        yield ResponseEntity.ok(null);
      }
      case LoginOutcome.TotpChallenge challenge ->
          ResponseEntity.ok(new LoginChallengeResponse(true, challenge.challengeId()));
    };
  }

  @Operation(
      summary = "Complete a TOTP login challenge",
      description =
          "Completes a login that POST /api/v1/auth/login flagged totpRequired, with either a live"
              + " TOTP code or a one-time recovery code (exactly one of the two). On success,"
              + " establishes the session exactly like a direct login. Rate-limited identically to"
              + " the password step (spec FS-2.2 V1); every failure reason returns the same generic"
              + " 401 as login itself.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Login completed; session cookie set"),
        @ApiResponse(
            responseCode = "400",
            description = "Neither or both of code/recoveryCode were provided (KH-USR-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description =
                "Unknown/expired challenge, TOTP-attempt lockout, or a wrong code (KH-RBC-0401,"
                    + " generic message)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/auth/totp")
  ResponseEntity<Void> completeTotp(
      @Valid @RequestBody TotpChallengeRequest req,
      HttpServletRequest request,
      HttpServletResponse response) {
    LoginResult result =
        authService.completeTotpChallenge(req.challengeId(), req.code(), req.recoveryCode());
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
          "Returns the authenticated user's username, display name, language, scopes,"
              + " mustChangePassword (spec FS-2.2 D5), tenantSlug, and totpEnabled (KH-2.4x). This"
              + " is the one endpoint exempt from the forced-password-change gate"
              + " (rbac.security.PasswordChangeEnforcementFilter) — every other authenticated call"
              + " returns 403 KH-USR-0403 while mustChangePassword is true, so a client should call"
              + " this endpoint right after login to detect the state and route to the change"
              + " screen before attempting anything else.",
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
            view.username(),
            view.displayNameI18n(),
            view.preferredLang(),
            actor.scopes(),
            view.mustChangePassword(),
            TenantContext.currentSlug(),
            view.totpEnabled()));
  }

  @Operation(
      summary = "Create an API key",
      description =
          "The response's rawKey is shown exactly once — the platform stores only its SHA-256"
              + " hash and prefix (spec FS-0.6b §4). tenantId (spec FS-2.1) defaults to the"
              + " caller's own tenant, requiring only the tenant:admin scope (spec FS-2.2 V4)."
              + " Naming a tenantId other than the caller's own — a platform admin provisioning a"
              + " newly onboarded tenant's first key — additionally requires the platform:admin"
              + " scope (spec FS-2.2 D4), enforced by shared.OnBehalfOfExecutor and recorded as"
              + " AuditAction.ON_BEHALF_OF.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Key created"),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the tenant:admin scope, or (when tenantId names another tenant) missing"
                    + " the platform:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "The named tenantId does not exist (KH-TNT-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/admin/api-keys")
  ResponseEntity<CreateApiKeyResponse> createApiKey(@Valid @RequestBody CreateApiKeyRequest req) {
    UUID callerTenant = TenantContext.current();
    CreatedApiKey created;
    if (req.tenantId() == null || req.tenantId().equals(callerTenant)) {
      created = apiKeyService.create(req.ownerType(), req.ownerId(), req.scopes());
    } else {
      // Spec FS-2.2 D4: naming a foreign tenantId is a cross-tenant on-behalf-of action — gated on
      // platform:admin by OnBehalfOfExecutor, not by this endpoint's baseline tenant:admin scope
      // (a request body's tenantId can't be inspected by a URL-pattern SecurityConfig rule).
      TenantRef target =
          tenantDirectory
              .findById(req.tenantId())
              .orElseThrow(() -> new NotFoundException(ErrorCode.KH_TNT_0404, "tenant.not-found"));
      created =
          onBehalfOf.runAsTenant(
              target.id(),
              target.slug(),
              () ->
                  apiKeyService.create(req.ownerType(), req.ownerId(), req.scopes(), target.id()));
    }
    return ResponseEntity.ok(
        new CreateApiKeyResponse(created.id(), created.keyPrefix(), created.rawKey()));
  }

  @Operation(
      summary = "Revoke an API key",
      description =
          "The key stops authenticating on the very next request (spec FS-0.6b DoD #5)."
              + " Idempotent — revoking an already-revoked or unknown key still returns 200."
              + " Requires the tenant:admin scope (spec FS-2.2 V4); RLS scopes visibility to the"
              + " caller's own tenant's keys regardless.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Revoked (or already inactive)"),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the tenant:admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/admin/api-keys/{id}/revoke")
  ResponseEntity<Void> revokeApiKey(@PathVariable String id) {
    apiKeyService.revoke(UUID.fromString(id));
    return ResponseEntity.ok().build();
  }
}
