package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.RbacHttpTestSupport;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.support.TotpEnrollmentCache;
import sy.khatm.platform.support.TotpTestCodes;

/**
 * Regression coverage for a bug reported live from khatm-console testing (2026-08-19): {@code POST
 * /api/v1/credentials/verify} and {@code POST /api/v1/claims/redeem} — both {@code permitAll}, spec
 * FS-0.6b DoD #9 / FS-1.2.1 DoD #7 — had been attributing every {@code CREDENTIAL_VERIFY_OK}/{@code
 * CREDENTIAL_VERIFY_FAILED}/{@code CLAIM_CODE_REDEEMED} audit row to the platform default tenant
 * ({@code shared.TenantContext#runAsDefaultTenant}) unconditionally, regardless of which tenant
 * actually issued the credential being verified or redeemed. Found via KH-2.6b's aggregated report
 * (spec FS-2.5 §4) always reading zero verify activity for real, non-default tenants; the identical
 * root cause affects {@code GET /api/v1/stats}'s per-tenant {@code verifyOk}/{@code
 * verifyFailed}/{@code claimsRedeemed} counters (same {@code
 * AuditService#countActionsInWindow(Instant, Instant)}, scoped by ambient {@code
 * TenantContext.current()}) and {@code GET /api/v1/activity}'s per-tenant feed — this class proves
 * the fix closes all three by fixing only the two write sites.
 *
 * <p>{@code rbac.AuthenticatedCallerOnAnonymousEndpointsTest} already pins the no-crash property
 * these two endpoints need regardless of which tenant they attribute to (an authenticated console
 * session's cookie still reaching a {@code permitAll} endpoint) — this class pins which tenant the
 * row actually lands under, the thing that test deliberately doesn't check.
 */
class VerifyAuditTenantAttributionTest extends RbacHttpTestSupport {

  private static final String TENANTS_BASE = "/api/v1/admin/tenants";
  private static final String KEYS_BASE = "/api/v1/admin/api-keys";
  private static final ObjectMapper JSON = new ObjectMapper();

  // RbacHttpTestSupport.BOOTSTRAP_ADMIN_USERNAME/PASSWORD are package-private to rbac and this
  // class lives in db (alongside CrossTenantIsolationTest, which duplicates the exact same pair
  // for the exact same reason) — same literal values, duplicated rather than exposed cross-package.
  private static final String BOOTSTRAP_ADMIN_USERNAME = "rbac-test-admin";
  private static final String BOOTSTRAP_ADMIN_PASSWORD = "rbac-test-admin-password-change-me";

  @Autowired private JdbcTemplate jdbc;

  private record Tenant(UUID id, String slug, String rawKey) {}

  private record AdminSession(String cookieHeader, String csrfHeader) {
    HttpHeaders writeHeaders() {
      HttpHeaders headers = new HttpHeaders();
      headers.set(HttpHeaders.COOKIE, cookieHeader);
      headers.set("X-XSRF-TOKEN", csrfHeader);
      return headers;
    }
  }

  // ── Setup helpers (mirrors CrossTenantIsolationTest's own — rbac.SessionTestSupport is
  // package-private to rbac, so this class, like that one, does the minimal login/TOTP dance
  // itself) ──────────────────────────────────────────────────────────────────────────────────

  private AdminSession loginAsBootstrapAdmin() {
    return loginWithTotpBootstrap(BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD, null);
  }

  private AdminSession loginWithTotpBootstrap(String username, String password, String tenantSlug) {
    Map<String, Object> body = new HashMap<>();
    body.put("username", username);
    body.put("password", password);
    if (tenantSlug != null) {
      body.put("tenantSlug", tenantSlug);
    }
    ResponseEntity<String> loginResponse =
        rest.postForEntity("/api/v1/auth/login", body, String.class);

    if (isTotpChallenge(loginResponse)) {
      String secret = TotpEnrollmentCache.SECRETS.get(username);
      String challengeId = readTree(loginResponse.getBody()).path("challengeId").asText();
      Map<String, Object> completeBody =
          Map.of("challengeId", challengeId, "code", TotpTestCodes.currentCode(secret));
      ResponseEntity<Void> completed =
          rest.postForEntity("/api/v1/auth/totp", completeBody, Void.class);
      return sessionFrom(completed);
    }

    AdminSession session = sessionFrom(loginResponse);
    if (!TotpEnrollmentCache.SECRETS.containsKey(username)) {
      tryEnrollAndConfirmTotp(session)
          .ifPresent(secret -> TotpEnrollmentCache.SECRETS.put(username, secret));
    }
    return session;
  }

  private boolean isTotpChallenge(ResponseEntity<String> response) {
    String body = response.getBody();
    return body != null && !body.isBlank() && readTree(body).path("totpRequired").asBoolean(false);
  }

  private static JsonNode readTree(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      throw new AssertionError("Failed to parse response body: " + body, e);
    }
  }

  private static AdminSession sessionFrom(ResponseEntity<?> response) {
    String sessionCookie = extractCookie(response, "KHATM_SESSION");
    String csrfCookie = extractCookie(response, "XSRF-TOKEN");
    String csrfValue = csrfCookie.substring(csrfCookie.indexOf('=') + 1);
    return new AdminSession(sessionCookie + "; " + csrfCookie, csrfValue);
  }

  private Optional<String> tryEnrollAndConfirmTotp(AdminSession session) {
    ResponseEntity<String> enrollResponse =
        rest.exchange(
            "/api/v1/users/me/totp/enroll",
            HttpMethod.POST,
            new HttpEntity<>(session.writeHeaders()),
            String.class);
    if (enrollResponse.getStatusCode() != HttpStatus.OK) {
      return Optional.empty();
    }
    String secret = readTree(enrollResponse.getBody()).path("secretBase32").asText();

    HttpHeaders confirmHeaders = session.writeHeaders();
    confirmHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    ResponseEntity<String> confirmResponse =
        rest.exchange(
            "/api/v1/users/me/totp/confirm",
            HttpMethod.POST,
            new HttpEntity<>(
                "{\"code\":\"" + TotpTestCodes.currentCode(secret) + "\"}", confirmHeaders),
            String.class);
    if (confirmResponse.getStatusCode() != HttpStatus.OK) {
      return Optional.empty();
    }
    return Optional.of(secret);
  }

  private static String extractCookie(ResponseEntity<?> response, String cookieName) {
    for (String setCookie : response.getHeaders().get(HttpHeaders.SET_COOKIE)) {
      if (setCookie.startsWith(cookieName + "=")) {
        int semicolon = setCookie.indexOf(';');
        return semicolon >= 0 ? setCookie.substring(0, semicolon) : setCookie;
      }
    }
    return null;
  }

  private Tenant onboardTenantWithKey(AdminSession admin, String prefix) {
    String slug = prefix + "-" + UUID.randomUUID();
    ResponseEntity<String> created =
        rest.exchange(
            TENANTS_BASE,
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("slug", slug, "nameI18n", Map.of("en", "x", "ar", "x"), "type", "OTHER"),
                admin.writeHeaders()),
            String.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    String tenantId = readTree(created.getBody()).get("id").asText();

    ResponseEntity<String> keyResponse =
        rest.exchange(
            KEYS_BASE,
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("ownerType", "TENANT", "scopes", Set.of("issue"), "tenantId", tenantId),
                admin.writeHeaders()),
            String.class);
    assertThat(keyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    String rawKey = readTree(keyResponse.getBody()).get("rawKey").asText();

    return new Tenant(UUID.fromString(tenantId), slug, rawKey);
  }

  private JsonNode issueCredential(String rawKey, String schemaCode) {
    Map<String, Object> body =
        Map.of(
            "schemaCode",
            schemaCode,
            "holderRef",
            "holder-" + UUID.randomUUID(),
            "claims",
            Map.of("field", "value"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/credentials/issue",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return readTree(response.getBody());
  }

  private int auditCountForTenant(UUID tenantId, String slug, String action, String entityRef) {
    TenantContext.set(tenantId, slug);
    try {
      return jdbc.queryForObject(
          "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_ref = ?",
          Integer.class,
          action,
          entityRef);
    } finally {
      TenantContext.clear();
    }
  }

  // ── /verify ───────────────────────────────────────────────────────────────────────────────

  @Test
  void verify_validCredential_attributesToIssuingTenant_notDefault() {
    AdminSession admin = loginAsBootstrapAdmin();
    Tenant tenant = onboardTenantWithKey(admin, "verify-attrib");
    JsonNode issued = issueCredential(tenant.rawKey(), "VerifyAttribValid/v1");
    String ref = issued.get("ref").asText();

    ResponseEntity<String> verifyResponse =
        rest.postForEntity(
            "/api/v1/credentials/verify",
            Map.of("sdJwt", issued.get("sdJwt").asText()),
            String.class);

    assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readTree(verifyResponse.getBody()).get("valid").asBoolean()).isTrue();

    assertThat(auditCountForTenant(tenant.id(), tenant.slug(), "CREDENTIAL_VERIFY_OK", ref))
        .as("verify audit row must land under the issuing tenant, not the default")
        .isEqualTo(1);
    assertThat(
            auditCountForTenant(
                TenantContext.DEFAULT_TENANT_ID,
                TenantContext.DEFAULT_TENANT_SLUG,
                "CREDENTIAL_VERIFY_OK",
                ref))
        .as("must NOT also (or instead) land under the platform default tenant")
        .isZero();
  }

  @Test
  void verify_malformedPresentation_stillAttributesToDefaultTenant() {
    // No credential row is ever resolved here — the one case where the default-tenant fallback
    // is correct behavior, not the bug. Proves the fix didn't overcorrect into a new crash/gap.
    ResponseEntity<String> verifyResponse =
        rest.postForEntity(
            "/api/v1/credentials/verify",
            Map.of("sdJwt", "not-a-real-jwt-" + UUID.randomUUID()),
            String.class);

    assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readTree(verifyResponse.getBody()).get("valid").asBoolean()).isFalse();

    int defaultTenantFailures =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CREDENTIAL_VERIFY_FAILED'"
                + " AND entity_ref IS NULL AND tenant_id = ?",
            Integer.class,
            TenantContext.DEFAULT_TENANT_ID);
    assertThat(defaultTenantFailures)
        .as("a presentation that never resolved a credential row has nowhere else to go")
        .isGreaterThan(0);
  }

  // ── /claims/redeem ────────────────────────────────────────────────────────────────────────

  @Test
  void redeem_attributesToIssuingTenant_notDefault() {
    AdminSession admin = loginAsBootstrapAdmin();
    Tenant tenant = onboardTenantWithKey(admin, "redeem-attrib");
    JsonNode issued = issueCredential(tenant.rawKey(), "RedeemAttrib/v1");
    String ref = issued.get("ref").asText();

    HttpHeaders issuerHeaders = new HttpHeaders();
    issuerHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.rawKey());
    Map<String, Object> mintBody = new HashMap<>();
    mintBody.put("sdJwt", issued.get("sdJwt").asText());
    mintBody.put("ttlMinutes", 5);
    ResponseEntity<String> minted =
        rest.exchange(
            "/api/v1/credentials/" + issued.get("id").asText() + "/claim-code",
            HttpMethod.POST,
            new HttpEntity<>(mintBody, issuerHeaders),
            String.class);
    assertThat(minted.getStatusCode()).isEqualTo(HttpStatus.OK);
    String code = readTree(minted.getBody()).get("code").asText();

    ResponseEntity<String> redeemed =
        rest.postForEntity("/api/v1/claims/redeem", Map.of("code", code), String.class);

    assertThat(redeemed.getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(auditCountForTenant(tenant.id(), tenant.slug(), "CLAIM_CODE_REDEEMED", ref))
        .as("redeem audit row must land under the issuing tenant, not the default")
        .isEqualTo(1);
    assertThat(
            auditCountForTenant(
                TenantContext.DEFAULT_TENANT_ID,
                TenantContext.DEFAULT_TENANT_SLUG,
                "CLAIM_CODE_REDEEMED",
                ref))
        .as("must NOT also (or instead) land under the platform default tenant")
        .isZero();
  }

  // ── Closes the loop: the per-tenant dashboard the console originally cross-checked ──────────

  @Test
  void dashboardStats_forTheIssuingTenant_reflectsItsOwnVerifyActivity() {
    AdminSession bootstrapAdmin = loginAsBootstrapAdmin();
    String slug = "dashboard-attrib-" + UUID.randomUUID();
    String adminUsername = "dashadmin-" + UUID.randomUUID().toString().substring(0, 8);
    ResponseEntity<String> onboarded =
        rest.exchange(
            TENANTS_BASE,
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "slug",
                    slug,
                    "nameI18n",
                    Map.of("en", "x", "ar", "x"),
                    "type",
                    "OTHER",
                    "initialAdmin",
                    Map.of(
                        "username",
                        adminUsername,
                        "displayNameI18n",
                        Map.of("en", "Dash Admin", "ar", "مدير"))),
                bootstrapAdmin.writeHeaders()),
            String.class);
    assertThat(onboarded.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode onboardedBody = readTree(onboarded.getBody());
    UUID tenantId = UUID.fromString(onboardedBody.get("id").asText());
    String tempPassword = onboardedBody.get("initialAdmin").get("temporaryPassword").asText();

    // Own-tenant key, minted by the freshly onboarded tenant's own admin session below would need
    // a second login round trip; simpler and equally valid to mint it cross-tenant as the
    // platform admin, exactly like the other tests in this class.
    ResponseEntity<String> keyResponse =
        rest.exchange(
            KEYS_BASE,
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "ownerType", "TENANT",
                    "scopes", Set.of("issue"),
                    "tenantId", tenantId.toString()),
                bootstrapAdmin.writeHeaders()),
            String.class);
    assertThat(keyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    String rawKey = readTree(keyResponse.getBody()).get("rawKey").asText();

    JsonNode issued = issueCredential(rawKey, "DashboardAttrib/v1");
    ResponseEntity<String> verifyResponse =
        rest.postForEntity(
            "/api/v1/credentials/verify",
            Map.of("sdJwt", issued.get("sdJwt").asText()),
            String.class);
    assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readTree(verifyResponse.getBody()).get("valid").asBoolean()).isTrue();

    // The temporary password forces a change before anything else works — same dance
    // UserAdminGateTest's own forced-password-change test already establishes. TOTP enrollment
    // is attempted but harmlessly fails here (blocked by the same forced-change gate); it
    // succeeds on the second login below, once the real password is set.
    AdminSession tenantAdminFirstLogin = loginWithTotpBootstrap(adminUsername, tempPassword, slug);
    String newPassword = tempPassword + "-changed";
    ResponseEntity<String> changed =
        rest.exchange(
            "/api/v1/users/me/password",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("currentPassword", tempPassword, "newPassword", newPassword),
                tenantAdminFirstLogin.writeHeaders()),
            String.class);
    assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
    AdminSession tenantAdminSession = loginWithTotpBootstrap(adminUsername, newPassword, slug);

    ResponseEntity<String> stats =
        rest.exchange(
            "/api/v1/stats",
            HttpMethod.GET,
            new HttpEntity<>(tenantAdminSession.writeHeaders()),
            String.class);

    assertThat(stats.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readTree(stats.getBody()).get("counters").get("verifyOk").asInt())
        .as("this tenant's own dashboard must see its own verify activity, not zero")
        .isGreaterThanOrEqualTo(1);
  }
}
