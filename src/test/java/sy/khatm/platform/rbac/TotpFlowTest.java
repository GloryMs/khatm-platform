package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.support.TotpTestCodes;

/**
 * Spec FS-2.2 V1 — TOTP second-factor enrollment, the login challenge (code + recovery-code paths,
 * rate-limited identically to the password step), the mandatory-enrollment wall, and both reset
 * paths (tenant:admin self-tenant, platform:admin on-behalf-of).
 *
 * <p>Deliberately does not use {@link SessionTestSupport}'s transparent auto-enrollment for the
 * specific users under test here (only for the bootstrap admin, to drive the admin-only actions
 * these tests need) — each test drives its own subject's login/enroll/confirm/challenge with raw
 * HTTP calls so it can assert on every intermediate response.
 */
class TotpFlowTest extends RbacHttpTestSupport {

  private static final String USERS_BASE = "/api/v1/users";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private JdbcTemplate jdbc;

  private static String uniqueUsername(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private int auditCount(String action, String entityRef) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_ref = ?",
        Integer.class,
        action,
        entityRef);
  }

  /**
   * Creates a fresh ISSUER_OPERATOR (holds {@code revoke}, a mandatory-2FA scope) and logs them in
   * with a real (non-temporary) password, returning a clean session with no TOTP enrolled yet.
   */
  private AuthenticatedSession freshMandatoryScopeSession(String username) throws Exception {
    AuthenticatedSession admin =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            USERS_BASE,
            admin,
            Map.of(
                "username",
                username,
                "displayNameI18n",
                Map.of("en", "Totp Test", "ar", "اختبار"),
                "roles",
                List.of("ISSUER_OPERATOR")));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    String tempPassword = JSON.readTree(created.getBody()).get("temporaryPassword").asText();

    ResponseEntity<String> firstLogin = rawLogin(username, tempPassword);
    AuthenticatedSession pending = sessionFrom(firstLogin);
    ResponseEntity<String> changed =
        rest.exchange(
            "/api/v1/users/me/password",
            HttpMethod.POST,
            pending.writeHeaders(
                Map.of("currentPassword", tempPassword, "newPassword", "a-real-password-123")),
            String.class);
    assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> secondLogin = rawLogin(username, "a-real-password-123");
    assertThat(isTotpChallenge(secondLogin)).as("fresh user has no TOTP yet").isFalse();
    return sessionFrom(secondLogin);
  }

  private ResponseEntity<String> rawLogin(String username, String password) {
    return rest.postForEntity(
        "/api/v1/auth/login", Map.of("username", username, "password", password), String.class);
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

  private static AuthenticatedSession sessionFrom(ResponseEntity<?> response) {
    String sessionCookie = SessionTestSupport.extractCookie(response, "KHATM_SESSION");
    String csrfCookie = SessionTestSupport.extractCookie(response, "XSRF-TOKEN");
    assertThat(sessionCookie).as("KHATM_SESSION cookie must be set").isNotNull();
    assertThat(csrfCookie).as("XSRF-TOKEN cookie must be set").isNotNull();
    String csrfValue = csrfCookie.substring(csrfCookie.indexOf('=') + 1);
    return new AuthenticatedSession(sessionCookie, csrfCookie, csrfValue);
  }

  private ResponseEntity<String> enroll(AuthenticatedSession session) {
    return rest.exchange(
        "/api/v1/users/me/totp/enroll", HttpMethod.POST, session.writeHeaders(), String.class);
  }

  private ResponseEntity<String> confirm(AuthenticatedSession session, String code) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(HttpHeaders.COOKIE, session.sessionCookie() + "; " + session.csrfCookie());
    headers.set("X-XSRF-TOKEN", session.csrfValue());
    return rest.exchange(
        "/api/v1/users/me/totp/confirm",
        HttpMethod.POST,
        new HttpEntity<>("{\"code\":\"" + code + "\"}", headers),
        String.class);
  }

  // ── Enrollment ───────────────────────────────────────────────────────────────────────────

  @Test
  void enrollThenConfirm_activatesTotp_returnsTenRecoveryCodes_andAudits() throws Exception {
    String username = uniqueUsername("totp-enroll");
    AuthenticatedSession session = freshMandatoryScopeSession(username);

    ResponseEntity<String> enrollResponse = enroll(session);
    assertThat(enrollResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode enrolled = readTree(enrollResponse.getBody());
    String secret = enrolled.get("secretBase32").asText();
    assertThat(secret).isNotBlank();
    assertThat(enrolled.get("otpAuthUri").asText()).startsWith("otpauth://totp/");

    ResponseEntity<String> confirmResponse = confirm(session, TotpTestCodes.currentCode(secret));
    assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode confirmed = readTree(confirmResponse.getBody());
    List<String> recoveryCodes =
        JSON.convertValue(
            confirmed.get("recoveryCodes"),
            JSON.getTypeFactory().constructCollectionType(List.class, String.class));
    assertThat(recoveryCodes).hasSize(10);
    assertThat(recoveryCodes).doesNotHaveDuplicates();
    assertThat(auditCount("USER_TOTP_ENROLLED", username)).isEqualTo(1);
  }

  @Test
  void confirm_wrongCode_returns400() throws Exception {
    String username = uniqueUsername("totp-wrongcode");
    AuthenticatedSession session = freshMandatoryScopeSession(username);
    enroll(session);

    ResponseEntity<String> confirmResponse = confirm(session, "000000");

    assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(readTree(confirmResponse.getBody()).get("code").asText()).isEqualTo("KH-USR-0400");
  }

  @Test
  void confirm_withNoPendingEnrollment_returns409() throws Exception {
    String username = uniqueUsername("totp-nopending");
    AuthenticatedSession session = freshMandatoryScopeSession(username);

    ResponseEntity<String> confirmResponse = confirm(session, "123456");

    assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(readTree(confirmResponse.getBody()).get("code").asText()).isEqualTo("KH-USR-1409");
  }

  @Test
  void enroll_whileAlreadyActive_returns409() throws Exception {
    String username = uniqueUsername("totp-alreadyactive");
    AuthenticatedSession session = freshMandatoryScopeSession(username);
    String secret = readTree(enroll(session).getBody()).get("secretBase32").asText();
    confirm(session, TotpTestCodes.currentCode(secret));

    ResponseEntity<String> secondEnroll = enroll(session);

    assertThat(secondEnroll.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(readTree(secondEnroll.getBody()).get("code").asText()).isEqualTo("KH-USR-1409");
  }

  // ── Mandatory-enrollment wall ────────────────────────────────────────────────────────────

  @Test
  void mandatoryScopeHolder_withNoTotp_isWalledExceptEnrollConfirmAndMe_thenUnwalledAfterConfirm()
      throws Exception {
    String username = uniqueUsername("totp-wall");
    AuthenticatedSession session = freshMandatoryScopeSession(username);

    // Any endpoint reachable by this role would do here (KH-USR-1403 fires before the target
    // endpoint's own scope check ever runs) — /api/v1/credentials is session-only, no specific
    // scope required (ScopeGuard.requireUserSession), so this proves the WALL itself, not merely
    // that ISSUER_OPERATOR lacks some other endpoint's own scope.
    ResponseEntity<String> blocked = SessionTestSupport.get(rest, "/api/v1/credentials", session);
    assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(readTree(blocked.getBody()).get("code").asText()).isEqualTo("KH-USR-1403");

    ResponseEntity<String> me = SessionTestSupport.get(rest, "/api/v1/auth/me", session);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    // KH-2.4x: totpEnabled reflects the actual enrollment state, not the mandatory-scope wall
    // this test also exercises — a walled user has no active TOTP yet by definition.
    assertThat(readTree(me.getBody()).get("totpEnabled").asBoolean())
        .as("no confirmed TOTP enrollment yet")
        .isFalse();

    String secret = readTree(enroll(session).getBody()).get("secretBase32").asText();
    assertThat(confirm(session, TotpTestCodes.currentCode(secret)).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ResponseEntity<String> unwalled = SessionTestSupport.get(rest, "/api/v1/credentials", session);
    assertThat(unwalled.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> meAfterConfirm =
        SessionTestSupport.get(rest, "/api/v1/auth/me", session);
    assertThat(meAfterConfirm.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readTree(meAfterConfirm.getBody()).get("totpEnabled").asBoolean())
        .as("TOTP confirmed above")
        .isTrue();
  }

  // ── Login challenge ──────────────────────────────────────────────────────────────────────

  @Test
  void login_withActiveTotp_issuesChallenge_completesWithCorrectCode() throws Exception {
    String username = uniqueUsername("totp-challenge");
    AuthenticatedSession session = freshMandatoryScopeSession(username);
    String secret = readTree(enroll(session).getBody()).get("secretBase32").asText();
    confirm(session, TotpTestCodes.currentCode(secret));

    ResponseEntity<String> login = rawLogin(username, "a-real-password-123");
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(isTotpChallenge(login)).isTrue();
    String challengeId = readTree(login.getBody()).get("challengeId").asText();

    ResponseEntity<String> completed =
        rest.postForEntity(
            "/api/v1/auth/totp",
            Map.of("challengeId", challengeId, "code", TotpTestCodes.currentCode(secret)),
            String.class);

    assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(SessionTestSupport.extractCookie(completed, "KHATM_SESSION")).isNotNull();
  }

  @Test
  void totpChallenge_wrongCodeRepeatedly_locksOutMirroringPassword_thenRecoversAfterWindow()
      throws Exception {
    String username = uniqueUsername("totp-lockout");
    AuthenticatedSession session = freshMandatoryScopeSession(username);
    String secret = readTree(enroll(session).getBody()).get("secretBase32").asText();
    confirm(session, TotpTestCodes.currentCode(secret));

    ResponseEntity<String> login = rawLogin(username, "a-real-password-123");
    String challengeId = readTree(login.getBody()).get("challengeId").asText();

    // khatm.auth.lockout.max-attempts=5 (RbacHttpTestSupport) — the TOTP-attempt counter mirrors
    // it exactly (rbac.domain.AuthService), so 5 wrong attempts trip the same generic lockout.
    for (int i = 0; i < 5; i++) {
      ResponseEntity<String> wrong =
          rest.postForEntity(
              "/api/v1/auth/totp",
              Map.of("challengeId", challengeId, "code", "000000"),
              String.class);
      assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // Locked out now — even the CORRECT code is rejected until the window elapses.
    ResponseEntity<String> correctButLocked =
        rest.postForEntity(
            "/api/v1/auth/totp",
            Map.of("challengeId", challengeId, "code", TotpTestCodes.currentCode(secret)),
            String.class);
    assertThat(correctButLocked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    // khatm.auth.lockout.window=2s (RbacHttpTestSupport) — wait it out, then the correct code
    // works (a fresh login is needed since this challenge's own TTL may still be alive, but the
    // per-tenant+username lockout key is what actually gated us, and it has now expired).
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              ResponseEntity<String> retryLogin = rawLogin(username, "a-real-password-123");
              String retryChallengeId = readTree(retryLogin.getBody()).get("challengeId").asText();
              ResponseEntity<String> retry =
                  rest.postForEntity(
                      "/api/v1/auth/totp",
                      Map.of(
                          "challengeId",
                          retryChallengeId,
                          "code",
                          TotpTestCodes.currentCode(secret)),
                      String.class);
              assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
            });
  }

  @Test
  void totpChallenge_recoveryCode_completesLogin_decrementsRemaining_andCannotBeReused()
      throws Exception {
    String username = uniqueUsername("totp-recovery");
    AuthenticatedSession session = freshMandatoryScopeSession(username);
    String secret = readTree(enroll(session).getBody()).get("secretBase32").asText();
    JsonNode confirmed = readTree(confirm(session, TotpTestCodes.currentCode(secret)).getBody());
    List<String> recoveryCodes =
        JSON.convertValue(
            confirmed.get("recoveryCodes"),
            JSON.getTypeFactory().constructCollectionType(List.class, String.class));
    String recoveryCode = recoveryCodes.get(0);

    ResponseEntity<String> login = rawLogin(username, "a-real-password-123");
    String challengeId = readTree(login.getBody()).get("challengeId").asText();

    ResponseEntity<String> completed =
        rest.postForEntity(
            "/api/v1/auth/totp",
            Map.of("challengeId", challengeId, "recoveryCode", recoveryCode),
            String.class);
    assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(auditCount("USER_TOTP_RECOVERY_CODE_USED", username)).isEqualTo(1);

    // The same recovery code cannot be reused on a fresh login challenge.
    ResponseEntity<String> secondLogin = rawLogin(username, "a-real-password-123");
    String secondChallengeId = readTree(secondLogin.getBody()).get("challengeId").asText();
    ResponseEntity<String> reused =
        rest.postForEntity(
            "/api/v1/auth/totp",
            Map.of("challengeId", secondChallengeId, "recoveryCode", recoveryCode),
            String.class);
    assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // ── Admin reset ──────────────────────────────────────────────────────────────────────────

  @Test
  void tenantAdmin_resetsUsersTotp_clearsEnrollment_andAudits() throws Exception {
    String username = uniqueUsername("totp-reset");
    AuthenticatedSession session = freshMandatoryScopeSession(username);
    String secret = readTree(enroll(session).getBody()).get("secretBase32").asText();
    confirm(session, TotpTestCodes.currentCode(secret));
    UUID userId = userIdByUsername(username);

    AuthenticatedSession admin =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    ResponseEntity<String> reset =
        SessionTestSupport.post(rest, USERS_BASE + "/" + userId + "/totp/reset", admin, null);

    assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(auditCount("USER_TOTP_RESET", username)).isEqualTo(1);

    ResponseEntity<String> loginAfterReset = rawLogin(username, "a-real-password-123");
    assertThat(isTotpChallenge(loginAfterReset))
        .as("TOTP was reset — login should not challenge anymore")
        .isFalse();
  }

  @Test
  void platformAdmin_resetsAnotherTenantsUsersTotp_onBehalfOf_clearsEnrollment_andAudits()
      throws Exception {
    AuthenticatedSession platformAdmin =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String slug = "totp-obo-" + UUID.randomUUID();
    String adminUsername = uniqueUsername("totp-obo-admin");
    ResponseEntity<String> onboarded =
        SessionTestSupport.post(
            rest,
            "/api/v1/admin/tenants",
            platformAdmin,
            Map.of(
                "slug",
                slug,
                "nameI18n",
                Map.of("en", "Totp OBO", "ar", "اختبار"),
                "type",
                "OTHER",
                "initialAdmin",
                Map.of(
                    "username",
                    adminUsername,
                    "displayNameI18n",
                    Map.of("en", "Otp Admin", "ar", "مدير"))));
    assertThat(onboarded.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode onboardedBody = readTree(onboarded.getBody());
    UUID tenantId = UUID.fromString(onboardedBody.get("id").asText());
    String tempPassword = onboardedBody.get("initialAdmin").get("temporaryPassword").asText();

    ResponseEntity<String> firstLogin = rawLoginForTenant(adminUsername, tempPassword, slug);
    AuthenticatedSession pending = sessionFrom(firstLogin);
    ResponseEntity<String> changed =
        rest.exchange(
            "/api/v1/users/me/password",
            HttpMethod.POST,
            pending.writeHeaders(
                Map.of("currentPassword", tempPassword, "newPassword", "a-real-password-123")),
            String.class);
    assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
    ResponseEntity<String> secondLogin =
        rawLoginForTenant(adminUsername, "a-real-password-123", slug);
    AuthenticatedSession tenantAdminSession = sessionFrom(secondLogin);
    String secret = readTree(enroll(tenantAdminSession).getBody()).get("secretBase32").asText();
    confirm(tenantAdminSession, TotpTestCodes.currentCode(secret));
    // The target tenant's users, listed on behalf of it (GET /admin/tenants/{id}/users) — avoids
    // a bare JDBC lookup, which would be RLS-scoped to whatever tenant is ambient for this test's
    // own thread (the default tenant, not the freshly onboarded one) and find nothing.
    ResponseEntity<String> tenantUsers =
        SessionTestSupport.get(rest, "/api/v1/admin/tenants/" + tenantId + "/users", platformAdmin);
    assertThat(tenantUsers.getStatusCode()).isEqualTo(HttpStatus.OK);
    UUID userId = null;
    for (JsonNode user : readTree(tenantUsers.getBody())) {
      if (adminUsername.equals(user.get("username").asText())) {
        userId = UUID.fromString(user.get("id").asText());
      }
    }
    assertThat(userId).as("onboarded admin must appear in the tenant's user list").isNotNull();

    ResponseEntity<String> reset =
        SessionTestSupport.post(
            rest,
            "/api/v1/admin/tenants/" + tenantId + "/users/" + userId + "/totp/reset",
            platformAdmin,
            null);

    assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
    // Not asserting the USER_TOTP_RESET audit row here: it is written under the target tenant
    // (OnBehalfOfExecutor switches TenantContext before the write), which this test's own bare
    // JDBC template can't see (RLS-scoped to whatever tenant is ambient for this thread — the
    // default one, not the freshly onboarded target) — already covered by the tenant:admin
    // self-reset test above, which resets a default-tenant user and can see its own audit row.

    ResponseEntity<String> loginAfterReset =
        rawLoginForTenant(adminUsername, "a-real-password-123", slug);
    assertThat(isTotpChallenge(loginAfterReset))
        .as("TOTP was reset on behalf of the tenant — login should not challenge anymore")
        .isFalse();
  }

  private ResponseEntity<String> rawLoginForTenant(String username, String password, String slug) {
    return rest.postForEntity(
        "/api/v1/auth/login",
        Map.of("username", username, "password", password, "tenantSlug", slug),
        String.class);
  }

  private UUID userIdByUsername(String username) {
    return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", UUID.class, username);
  }
}
