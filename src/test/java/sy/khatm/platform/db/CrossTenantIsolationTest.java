package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.RbacHttpTestSupport;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.support.TotpEnrollmentCache;
import sy.khatm.platform.support.TotpTestCodes;

/**
 * KH-2.1-BE Part B, spec FS-2.1 D10 — the mandatory named leak suite, joining {@code
 * ModulithBoundariesTest}/{@code MessageBundleParityTest}/{@code ConcurrentConsumeTest}/{@code
 * MigrationCleanBootTest}. Seeds two tenants (A, B) via the real onboarding plane, issues a
 * credential under each, then proves isolation at three levels:
 *
 * <ul>
 *   <li><b>(a) HTTP layer</b>: tenant A's own API key fetching tenant B's credential by id gets a
 *       404 — {@code CredentialService#getView} does {@code credentials.findById(id)} with <em>no
 *       tenant filter of its own at all</em>, so this 404 can only come from RLS itself.
 *   <li><b>(b) Defense-in-depth</b>: a bare {@code SELECT} with no {@code WHERE tenant_id} clause
 *       whatsoever, run via the app's own {@code khatm_app}-authenticated {@link JdbcTemplate} with
 *       {@code app.tenant_id} set to tenant A, returns only tenant A's row even though tenant B's
 *       physically exists in the same table — RLS itself filters it, not any application code.
 *   <li><b>(c) Missing context, closed-fail</b>: the same bare {@code SELECT}, run over a raw JDBC
 *       connection with no Spring transaction at all (so {@code app.tenant_id} is never set)
 *       returns zero rows, proving an absent context hides everything rather than leaking it.
 * </ul>
 *
 * <p>{@code rbac.SessionTestSupport} is package-private to {@code rbac}, so — like {@code
 * tenant.web.StatusListControllerHttpTest} — this class (living in {@code db}) does the minimal
 * login-cookie dance itself rather than depending on it. It extends {@code RbacHttpTestSupport}
 * directly, though, so it shares the exact same cached context/database as every {@code rbac}
 * scope-gate test — {@link #loginAsAdmin} therefore also transparently enrolls/completes TOTP for
 * the shared bootstrap admin (spec FS-2.2 V1's mandatory-2FA gate would otherwise wall off {@code
 * POST /api/v1/admin/tenants} itself), sharing {@link TotpEnrollmentCache} with {@code
 * rbac.SessionTestSupport} rather than tracking its own separate secret — see that cache's own
 * Javadoc for why a separate one would break whichever of the two ran second.
 */
class CrossTenantIsolationTest extends RbacHttpTestSupport {

  private static final String TENANTS_BASE = "/api/v1/admin/tenants";
  private static final String KEYS_BASE = "/api/v1/admin/api-keys";
  private static final ObjectMapper JSON = new ObjectMapper();

  // RbacHttpTestSupport.BOOTSTRAP_ADMIN_USERNAME/PASSWORD are package-private to rbac and this
  // class lives in db (the mandatory-named-test package, alongside ConcurrentConsumeTest/
  // MigrationCleanBootTest) — same literal values, duplicated rather than exposed cross-package.
  private static final String BOOTSTRAP_ADMIN_USERNAME = "rbac-test-admin";
  private static final String BOOTSTRAP_ADMIN_PASSWORD = "rbac-test-admin-password-change-me";

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  private record Tenant(UUID id, String slug, String rawKey) {}

  private record AdminSession(String cookieHeader, String csrfHeader) {
    HttpHeaders writeHeaders() {
      HttpHeaders headers = new HttpHeaders();
      headers.set(HttpHeaders.COOKIE, cookieHeader);
      headers.set("X-XSRF-TOKEN", csrfHeader);
      return headers;
    }
  }

  private AdminSession loginAsAdmin() {
    ResponseEntity<String> loginResponse =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of("username", BOOTSTRAP_ADMIN_USERNAME, "password", BOOTSTRAP_ADMIN_PASSWORD),
            String.class);

    if (isTotpChallenge(loginResponse)) {
      String secret = TotpEnrollmentCache.SECRETS.get(BOOTSTRAP_ADMIN_USERNAME);
      String challengeId = readTree(loginResponse.getBody()).path("challengeId").asText();
      Map<String, Object> completeBody =
          Map.of("challengeId", challengeId, "code", TotpTestCodes.currentCode(secret));
      ResponseEntity<Void> completed =
          rest.postForEntity("/api/v1/auth/totp", completeBody, Void.class);
      return sessionFrom(completed);
    }

    AdminSession session = sessionFrom(loginResponse);
    if (!TotpEnrollmentCache.SECRETS.containsKey(BOOTSTRAP_ADMIN_USERNAME)) {
      tryEnrollAndConfirmTotp(session)
          .ifPresent(secret -> TotpEnrollmentCache.SECRETS.put(BOOTSTRAP_ADMIN_USERNAME, secret));
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
    confirmHeaders.setContentType(MediaType.APPLICATION_JSON);
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
    List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    if (setCookies == null) {
      return null;
    }
    for (String setCookie : setCookies) {
      if (setCookie.startsWith(cookieName + "=")) {
        int semicolon = setCookie.indexOf(';');
        return semicolon >= 0 ? setCookie.substring(0, semicolon) : setCookie;
      }
    }
    return null;
  }

  private Tenant onboardTenantWithKey(AdminSession admin, String prefix) throws Exception {
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
    String tenantId = JSON.readTree(created.getBody()).get("id").asText();

    ResponseEntity<String> keyResponse =
        rest.exchange(
            KEYS_BASE,
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("ownerType", "TENANT", "scopes", Set.of("issue"), "tenantId", tenantId),
                admin.writeHeaders()),
            String.class);
    assertThat(keyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    String rawKey = JSON.readTree(keyResponse.getBody()).get("rawKey").asText();

    return new Tenant(UUID.fromString(tenantId), slug, rawKey);
  }

  private String issueCredential(String rawKey, String schemaCode) throws Exception {
    return issueCredentialResponse(rawKey, schemaCode).get("id").asText();
  }

  private JsonNode issueCredentialResponse(String rawKey, String schemaCode) throws Exception {
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
    return JSON.readTree(response.getBody());
  }

  @Test
  void crossTenantReads_areIsolated_atHttpLayer_repositoryLayer_andWithMissingContext()
      throws Exception {
    AdminSession admin = loginAsAdmin();
    Tenant tenantA = onboardTenantWithKey(admin, "leak-a");
    Tenant tenantB = onboardTenantWithKey(admin, "leak-b");

    String credentialAId = issueCredential(tenantA.rawKey(), "CrossTenantLeakA/v1");
    String credentialBId = issueCredential(tenantB.rawKey(), "CrossTenantLeakB/v1");

    // (a) HTTP layer: tenant A's own key cannot fetch tenant B's credential by id — 404, zero
    // rows. CredentialService#getView has no tenant filter of its own; only RLS can produce this.
    HttpHeaders aHeaders = new HttpHeaders();
    aHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.rawKey());
    ResponseEntity<String> crossFetch =
        rest.exchange(
            "/api/v1/credentials/" + credentialBId,
            HttpMethod.GET,
            new HttpEntity<>(aHeaders),
            String.class);
    assertThat(crossFetch.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    // Sanity: tenant A CAN fetch its own credential the same way.
    ResponseEntity<String> ownFetch =
        rest.exchange(
            "/api/v1/credentials/" + credentialAId,
            HttpMethod.GET,
            new HttpEntity<>(aHeaders),
            String.class);
    assertThat(ownFetch.getStatusCode()).isEqualTo(HttpStatus.OK);

    // (b) Defense-in-depth: a bare SELECT with NO tenant_id predicate at all, run as khatm_app
    // (this suite's own app datasource) with app.tenant_id set to tenant A — only tenant A's row
    // comes back, even though tenant B's physically exists in the same table. The shared
    // TransactionalTestJdbcTemplateConfig wraps every jdbc call in its own fresh transaction, so
    // shared.TenantContextTransactionExecutionListener fires and picks up whatever TenantContext
    // is set on this thread at that moment (a plain ThreadLocal, unaffected by the transaction
    // boundary itself).
    TenantContext.set(tenantA.id(), tenantA.slug());
    try {
      List<String> visibleIds = jdbc.queryForList("SELECT id FROM credential", String.class);
      assertThat(visibleIds).contains(credentialAId).doesNotContain(credentialBId);
    } finally {
      TenantContext.clear();
    }

    // (c) Missing context, closed-fail: a raw JDBC connection with NO Spring transaction at all
    // (bypassing even the auto-wrapping test jdbc bean, which — precisely because it always opens
    // its own transaction — can no longer demonstrate a genuinely absent context on its own).
    // app.tenant_id was never set on this fresh connection/session, so current_setting(...,
    // true) resolves to NULL, matching no row at all.
    List<String> noContextRows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("SELECT id FROM credential")) {
      while (resultSet.next()) {
        noContextRows.add(resultSet.getString("id"));
      }
    }
    assertThat(noContextRows).isEmpty();
  }

  /**
   * KH-2.1 Part B regression pin: {@code CredentialService#verify} runs under {@code
   * SystemAccessExecutor} (no principal, no ambient tenant of its own) — before this fix, the
   * verify response's convenience {@code statusListUri} field was always built using whichever
   * tenant happened to be ambient (the platform default in practice, discovered via a live docker
   * compose e2e run, not by this test suite originally), leaking the wrong tenant's slug into the
   * URL regardless of which tenant actually issued the credential being verified. The credential's
   * own embedded JWT claim was always correct (issuance runs under the issuing tenant's own
   * authenticated context) — only this separate, independently-rebuilt response field was wrong.
   */
  @Test
  void verify_statusListUri_reflectsTheIssuingTenant_notTheAmbientDefault() throws Exception {
    AdminSession admin = loginAsAdmin();
    Tenant tenantA = onboardTenantWithKey(admin, "leak-verify-uri");

    JsonNode issued = issueCredentialResponse(tenantA.rawKey(), "CrossTenantVerifyUri/v1");
    String sdJwt = issued.get("sdJwt").asText();

    ResponseEntity<String> verifyResponse =
        rest.postForEntity("/api/v1/credentials/verify", Map.of("sdJwt", sdJwt), String.class);

    assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    String statusListUri = JSON.readTree(verifyResponse.getBody()).get("statusListUri").asText();
    assertThat(statusListUri).contains("/sl/" + tenantA.slug() + "/");
  }
}
