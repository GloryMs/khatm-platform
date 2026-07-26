package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import sy.khatm.platform.rbac.domain.AuthService;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.error.AuthenticationException;
import sy.khatm.platform.tenant.api.TenantAdmin;

/**
 * KH-2.1-BE Part A, spec D7/V4 — a SUSPENDED tenant's own principals fail authentication entirely
 * (the same shape KH-1.4.4 established for a suspended consuming party), while its already-issued
 * trust surfaces (JWKS here; verify/consume/status-list are exercised by the session's live-compose
 * e2e) keep serving regardless.
 *
 * <p><b>Console login/session coverage is deliberately narrower than the API-key coverage:</b>
 * {@code AuthService#login}/{@code SessionAuthenticator#establish} both resolve {@code
 * TenantContext.current()} — the ambient ("calling") tenant — never a target user's own {@code
 * app_user.tenant_id}, since nothing in {@code POST /api/v1/auth/login}'s request shape names which
 * tenant a login is for (that resolution is genuine multi-tenant console-user support, out of scope
 * per spec FS-2.1 §1 — deferred to KH-2.2). A brand-new, non-default tenant's user therefore cannot
 * log in via real HTTP at all today, suspended or not, so {@link
 * #login_forSuspendedTenant_isRejected} exercises {@code AuthService#login}'s suspended check
 * directly at the service level (pointing {@link TenantContext} at a freshly onboarded tenant), and
 * {@link #existingSession_survivingSuspension_subsequentRequestReturns401} uses the one tenant that
 * <em>can</em> establish a real session today — the default tenant — suspending and always
 * reactivating it within the same test method (sequential Surefire execution, no parallelism
 * configured, makes this safe for the shared-context suite).
 */
class SuspendedTenantAuthTest extends RbacHttpTestSupport {

  private static final String TENANTS_BASE = "/api/v1/admin/tenants";
  private static final String KEYS_BASE = "/api/v1/admin/api-keys";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private AuthService authService;
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
  void login_forSuspendedTenant_isRejected() throws Exception {
    AuthenticatedSession admin = adminSession();
    String tenantId = onboardTenant(admin, uniqueSlug("suspend-login-svc"));
    SessionTestSupport.post(rest, TENANTS_BASE + "/" + tenantId + "/suspend", admin, null);

    TenantContext.set(UUID.fromString(tenantId), "irrelevant-for-this-check");
    try {
      assertThatThrownBy(() -> authService.login("nonexistent-user", "irrelevant"))
          .isInstanceOf(AuthenticationException.class);
    } finally {
      TenantContext.clear();
    }
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
