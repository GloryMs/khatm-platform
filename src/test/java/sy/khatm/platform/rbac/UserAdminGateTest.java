package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;

/**
 * Spec FS-2.2 D5/D8 — the tenant user-management surface over real HTTP: the {@code tenant:admin}
 * gate (any actor-kind), the full create → roles → lock → unlock → disable → reset-password
 * lifecycle with audit rows, the last-active-admin guard's 409, and the forced-password-change gate
 * end-to-end (temporary-password login → every other call 403 KH-USR-0403 → self-service change →
 * flag cleared → normal access restored).
 */
class UserAdminGateTest extends RbacHttpTestSupport {

  private static final String BASE = "/api/v1/users";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;
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

  // ── Scope gate ────────────────────────────────────────────────────────────────────────────

  @Test
  void list_withNoCredential_returns401() {
    ResponseEntity<String> response = rest.getForEntity(BASE, String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void create_withTenantKeyMissingTenantAdminScope_returns403() throws Exception {
    CreatedApiKey issuerKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));

    ResponseEntity<String> response = createWithApiKey(issuerKey.rawKey(), uniqueUsername("gate"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void create_withTenantAdminApiKey_returns403_becauseApiKeysAreNotUserSessions() throws Exception {
    // requireScopeAndUserSession: tenant:admin scope alone is not enough — this surface is
    // console-session-only, no API key of any kind (spec FS-2.2 D5 implicit actor-kind decision,
    // matching the credential-search/stats family's "operator tool, not an integration" stance).
    CreatedApiKey tenantAdminKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("tenant:admin"));

    ResponseEntity<String> response =
        createWithApiKey(tenantAdminKey.rawKey(), uniqueUsername("gate-key"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  // ── Full lifecycle (tenant:admin session) ──────────────────────────────────────────────────

  @Test
  void fullLifecycle_createRolesLockUnlockDisableResetPassword_withAuditRows() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String username = uniqueUsername("lifecycle");

    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            BASE,
            session,
            Map.of(
                "username",
                username,
                "displayNameI18n",
                Map.of("en", "Test User", "ar", "مستخدم تجريبي"),
                "roles",
                List.of("ISSUER_OPERATOR")));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode createdBody = JSON.readTree(created.getBody());
    String id = createdBody.get("id").asText();
    assertThat(createdBody.get("temporaryPassword").asText()).isNotBlank();
    assertThat(auditCount("USER_CREATED", username)).isEqualTo(1);

    ResponseEntity<String> listed = SessionTestSupport.get(rest, BASE, session);
    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(listed.getBody()).contains(username);

    ResponseEntity<String> rolesChanged =
        SessionTestSupport.post(
            rest, BASE + "/" + id + "/roles", session, Map.of("roles", List.of("TENANT_ADMIN")));
    assertThat(rolesChanged.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(auditCount("USER_ROLES_CHANGED", username)).isEqualTo(1);

    ResponseEntity<String> locked =
        SessionTestSupport.post(rest, BASE + "/" + id + "/lock", session, null);
    assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(locked.getBody()).get("status").asText()).isEqualTo("LOCKED");
    assertThat(auditCount("USER_LOCKED", username)).isEqualTo(1);

    ResponseEntity<String> unlocked =
        SessionTestSupport.post(rest, BASE + "/" + id + "/unlock", session, null);
    assertThat(unlocked.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(unlocked.getBody()).get("status").asText()).isEqualTo("ACTIVE");
    assertThat(auditCount("USER_UNLOCKED", username)).isEqualTo(1);

    ResponseEntity<String> disabled =
        SessionTestSupport.post(rest, BASE + "/" + id + "/disable", session, null);
    assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(disabled.getBody()).get("status").asText()).isEqualTo("DISABLED");
    assertThat(auditCount("USER_DISABLED", username)).isEqualTo(1);

    ResponseEntity<String> reset =
        SessionTestSupport.post(rest, BASE + "/" + id + "/reset-password", session, null);
    assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(reset.getBody()).get("temporaryPassword").asText()).isNotBlank();
    assertThat(auditCount("USER_PASSWORD_RESET", username)).isEqualTo(1);
  }

  @Test
  void create_duplicateUsername_returns409() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String username = uniqueUsername("dup");
    Map<String, Object> body =
        Map.of(
            "username",
            username,
            "displayNameI18n",
            Map.of("en", "X", "ar", "X"),
            "roles",
            List.of());

    ResponseEntity<String> first = SessionTestSupport.post(rest, BASE, session, body);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> second = SessionTestSupport.post(rest, BASE, session, body);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(JSON.readTree(second.getBody()).get("code").asText()).isEqualTo("KH-USR-0409");
  }

  @Test
  void create_unknownRoleCode_returns400() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            BASE,
            session,
            Map.of(
                "username",
                uniqueUsername("badrole"),
                "displayNameI18n",
                Map.of("en", "X", "ar", "X"),
                "roles",
                List.of("NOT_A_REAL_ROLE")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-USR-0400");
  }

  // ── Last-active-admin guard ─────────────────────────────────────────────────────────────────

  @Test
  void disable_theSoleActiveAdmin_returns409() throws Exception {
    // The bootstrap admin is the tenant's sole PLATFORM_ADMIN (which also carries tenant:admin).
    // Disabling it must be refused, not merely inconvenient.
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String selfId =
        jdbc.queryForObject(
            "SELECT id::text FROM app_user WHERE username = ?",
            String.class,
            BOOTSTRAP_ADMIN_USERNAME);

    ResponseEntity<String> response =
        SessionTestSupport.post(rest, BASE + "/" + selfId + "/disable", session, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-USR-0423");
  }

  @Test
  void replaceRoles_removingTenantAdminFromTheSoleAdmin_returns409() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String selfId =
        jdbc.queryForObject(
            "SELECT id::text FROM app_user WHERE username = ?",
            String.class,
            BOOTSTRAP_ADMIN_USERNAME);

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            BASE + "/" + selfId + "/roles",
            session,
            Map.of("roles", List.of("ISSUER_OPERATOR")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-USR-0423");
  }

  @Test
  void disable_oneOfTwoAdmins_succeeds_leavingOneActiveAdmin() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String username = uniqueUsername("secondadmin");
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            BASE,
            session,
            Map.of(
                "username",
                username,
                "displayNameI18n",
                Map.of("en", "Second Admin", "ar", "مدير ثانٍ"),
                "roles",
                List.of("TENANT_ADMIN")));
    String secondAdminId = JSON.readTree(created.getBody()).get("id").asText();

    ResponseEntity<String> disabled =
        SessionTestSupport.post(rest, BASE + "/" + secondAdminId + "/disable", session, null);

    assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  // ── Forced password-change gate, end-to-end ────────────────────────────────────────────────

  @Test
  void temporaryPasswordLogin_blocksEveryCallExceptChangePassword_untilChanged() throws Exception {
    AuthenticatedSession adminSession =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String username = uniqueUsername("forcedchange");
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            BASE,
            adminSession,
            Map.of(
                "username",
                username,
                "displayNameI18n",
                Map.of("en", "Forced Change", "ar", "تغيير إجباري"),
                "roles",
                List.of("ISSUER_OPERATOR")));
    JsonNode createdBody = JSON.readTree(created.getBody());
    String temporaryPassword = createdBody.get("temporaryPassword").asText();

    AuthenticatedSession newSession = SessionTestSupport.login(rest, username, temporaryPassword);

    // GET /me is exempt from the gate specifically so the flag is discoverable — and it is true.
    ResponseEntity<String> me = SessionTestSupport.get(rest, "/api/v1/auth/me", newSession);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(me.getBody()).get("mustChangePassword").asBoolean()).isTrue();

    // Every OTHER authenticated call is blocked with the distinct forced-change code.
    ResponseEntity<String> blocked = SessionTestSupport.get(rest, BASE, newSession);
    assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(JSON.readTree(blocked.getBody()).get("code").asText()).isEqualTo("KH-USR-0403");

    // The self-service change endpoint itself is exempt and succeeds.
    ResponseEntity<String> changed =
        SessionTestSupport.post(
            rest,
            "/api/v1/users/me/password",
            newSession,
            Map.of("currentPassword", temporaryPassword, "newPassword", "a-real-password-123"));
    assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(auditCount("USER_PASSWORD_CHANGED", username)).isEqualTo(1);

    // The flag is cleared — /me now reports false, and normal access is restored on the session.
    ResponseEntity<String> afterChange =
        SessionTestSupport.get(rest, "/api/v1/auth/me", newSession);
    assertThat(afterChange.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(afterChange.getBody()).get("mustChangePassword").asBoolean())
        .isFalse();
  }

  private ResponseEntity<String> createWithApiKey(String rawKey, String username) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
    Map<String, Object> body =
        Map.of(
            "username",
            username,
            "displayNameI18n",
            Map.of("en", "X", "ar", "X"),
            "roles",
            List.of());
    return rest.exchange(BASE, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }
}
