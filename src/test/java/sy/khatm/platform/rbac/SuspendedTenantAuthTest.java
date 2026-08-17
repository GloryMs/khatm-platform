package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.tenant.api.TenantAdmin;

/**
 * KH-2.1-BE Part A, spec D7/V4 — a SUSPENDED tenant's own principals fail authentication entirely
 * (the same shape KH-1.4.4 established for a suspended consuming party), while its already-issued
 * trust surfaces (JWKS here; verify/consume/status-list are exercised by the session's live-compose
 * e2e) keep serving regardless.
 *
 * <p><b>Console login now resolves an explicitly named tenant (spec FS-2.2 — the optional {@code
 * tenantSlug} login field):</b> {@link #login_forSuspendedTenant_viaHttp_isRejected} and {@link
 * #login_forNonDefaultTenant_viaHttp_establishesSessionScopedToThatTenant} exercise the full real
 * HTTP path for a freshly onboarded, non-default tenant — both the suspended-tenant rejection and
 * the successful case, proving the established KH-2.1 machinery ({@code TenantContextFilter}, RLS,
 * the forced-password-change gate) needs no special-casing for a non-default tenant's session; a
 * successful {@code GET /api/v1/auth/me} against the resulting session is itself proof the session
 * carries the right tenant, since a wrong one would have RLS hide the user entirely (500, not a
 * wrong answer — see {@code shared.TenantContext}'s fail-fast guard). {@link
 * #existingSession_survivingSuspension_subsequentRequestReturns401} still uses the default tenant
 * specifically (suspending and always reactivating it within the same test method — sequential
 * Surefire execution, no parallelism configured, makes this safe for the shared-context suite), not
 * because a non-default tenant can't establish a session anymore, but because every other test
 * class in this run depends on the default tenant staying {@code ACTIVE}.
 */
class SuspendedTenantAuthTest extends RbacHttpTestSupport {

  private static final String TENANTS_BASE = "/api/v1/admin/tenants";
  private static final String KEYS_BASE = "/api/v1/admin/api-keys";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private TenantAdmin tenantAdmin;

  private AuthenticatedSession adminSession() {
    return SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
  }

  private String onboardTenant(AuthenticatedSession admin, String slug) throws Exception {
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            TENANTS_BASE,
            admin,
            Map.of("slug", slug, "nameI18n", Map.of("en", "x", "ar", "x"), "type", "OTHER"));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    return JSON.readTree(created.getBody()).get("id").asText();
  }

  /** Onboards a tenant with a first {@code TENANT_ADMIN}; returns {tenantId, temporaryPassword}. */
  private String[] onboardTenantWithAdmin(AuthenticatedSession admin, String slug, String username)
      throws Exception {
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            TENANTS_BASE,
            admin,
            Map.of(
                "slug",
                slug,
                "nameI18n",
                Map.of("en", "x", "ar", "x"),
                "type",
                "OTHER",
                "initialAdmin",
                Map.of("username", username, "displayNameI18n", Map.of("en", "x", "ar", "x"))));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(created.getBody());
    return new String[] {
      body.get("id").asText(), body.get("initialAdmin").get("temporaryPassword").asText()
    };
  }

  private String mintTenantKey(AuthenticatedSession admin, String tenantId, String scope)
      throws Exception {
    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            KEYS_BASE,
            admin,
            Map.of("ownerType", "TENANT", "scopes", Set.of(scope), "tenantId", tenantId));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return JSON.readTree(response.getBody()).get("rawKey").asText();
  }

  private ResponseEntity<String> issueWithKey(String rawKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
    Map<String, Object> body =
        Map.of(
            "schemaCode",
            "SuspendedTenantProbe/v1",
            "holderRef",
            "holder-" + UUID.randomUUID(),
            "claims",
            Map.of("field", "value"));
    return rest.exchange(
        "/api/v1/credentials/issue",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  @Test
  void suspendedTenant_apiKeyIssuance_returns401_thenWorksAgainAfterActivate() throws Exception {
    AuthenticatedSession admin = adminSession();
    String tenantId = onboardTenant(admin, uniqueSlug("suspend-key"));
    String rawKey = mintTenantKey(admin, tenantId, "issue");

    assertThat(issueWithKey(rawKey).getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> suspend =
        SessionTestSupport.post(rest, TENANTS_BASE + "/" + tenantId + "/suspend", admin, null);
    assertThat(suspend.getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(issueWithKey(rawKey).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    ResponseEntity<String> activate =
        SessionTestSupport.post(rest, TENANTS_BASE + "/" + tenantId + "/activate", admin, null);
    assertThat(activate.getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(issueWithKey(rawKey).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void login_forSuspendedTenant_viaHttp_isRejected() throws Exception {
    AuthenticatedSession admin = adminSession();
    String slug = uniqueSlug("suspend-login-http");
    String username = "suspend-admin-" + UUID.randomUUID();
    String[] onboarded = onboardTenantWithAdmin(admin, slug, username);
    String tenantId = onboarded[0];
    String temporaryPassword = onboarded[1];

    ResponseEntity<String> suspend =
        SessionTestSupport.post(rest, TENANTS_BASE + "/" + tenantId + "/suspend", admin, null);
    assertThat(suspend.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Even the tenant's own admin, with correct credentials, is rejected — the identical generic
    // 401 (spec D7) an unknown username or a bad password would produce, checked before any
    // credential comparison happens at all.
    ResponseEntity<String> loginAttempt =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of("username", username, "password", temporaryPassword, "tenantSlug", slug),
            String.class);
    assertThat(loginAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(JSON.readTree(loginAttempt.getBody()).get("code").asText()).isEqualTo("KH-RBC-0401");
  }

  @Test
  void login_forUnknownTenantSlug_isRejected_theSameGenericFailure() throws Exception {
    ResponseEntity<String> loginAttempt =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of(
                "username",
                "irrelevant",
                "password",
                "irrelevant",
                "tenantSlug",
                uniqueSlug("no-such-tenant")),
            String.class);
    assertThat(loginAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(JSON.readTree(loginAttempt.getBody()).get("code").asText()).isEqualTo("KH-RBC-0401");
  }

  @Test
  void login_forNonDefaultTenant_viaHttp_establishesSessionScopedToThatTenant() throws Exception {
    AuthenticatedSession admin = adminSession();
    String slug = uniqueSlug("login-http");
    String username = "login-admin-" + UUID.randomUUID();
    String[] onboarded = onboardTenantWithAdmin(admin, slug, username);
    String temporaryPassword = onboarded[1];

    // No special-casing needed anywhere downstream: TenantContextFilter, RLS, and the
    // forced-password-change gate all just work off whatever tenant the session carries.
    AuthenticatedSession session =
        SessionTestSupport.login(rest, username, temporaryPassword, slug);

    ResponseEntity<String> me = SessionTestSupport.get(rest, "/api/v1/auth/me", session);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode meBody = JSON.readTree(me.getBody());
    assertThat(meBody.get("username").asText()).isEqualTo(username);
    assertThat(meBody.get("mustChangePassword").asBoolean())
        .as("a freshly-created admin logs in with its one-time temporary password")
        .isTrue();
    // KH-2.4x: tenantSlug reflects the session's actual (non-default) tenant, not the default
    // one — closes platform ask C8 (the console's rotate-confirm dialog needs a human-legible
    // tenant identifier, not a signing key's opaque kid).
    assertThat(meBody.get("tenantSlug").asText()).isEqualTo(slug);
  }

  @Test
  void existingSession_survivingSuspension_subsequentRequestReturns401() throws Exception {
    UUID defaultTenantId = TenantContext.DEFAULT_TENANT_ID;
    AuthenticatedSession userSession =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    ResponseEntity<String> meBeforeSuspend =
        SessionTestSupport.get(rest, "/api/v1/auth/me", userSession);
    assertThat(meBeforeSuspend.getStatusCode()).isEqualTo(HttpStatus.OK);

    try {
      tenantAdmin.suspend(defaultTenantId);

      ResponseEntity<String> meAfterSuspend =
          SessionTestSupport.get(rest, "/api/v1/auth/me", userSession);
      assertThat(meAfterSuspend.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    } finally {
      // Always reactivate — this is the shared-context suite's own default tenant; every other
      // test class in this run depends on it staying ACTIVE.
      tenantAdmin.activate(defaultTenantId);
    }
  }

  @Test
  void suspendedTenant_jwksStillServes() throws Exception {
    AuthenticatedSession admin = adminSession();
    String slug = uniqueSlug("suspend-jwks");
    String tenantId = onboardTenant(admin, slug);

    ResponseEntity<String> suspend =
        SessionTestSupport.post(rest, TENANTS_BASE + "/" + tenantId + "/suspend", admin, null);
    assertThat(suspend.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> jwks =
        rest.getForEntity("/t/" + slug + "/.well-known/jwks.json", String.class);
    assertThat(jwks.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode keys = JSON.readTree(jwks.getBody()).get("keys");
    assertThat(keys).hasSizeGreaterThanOrEqualTo(1);
  }

  private static String uniqueSlug(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
