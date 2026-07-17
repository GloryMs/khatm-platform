package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import sy.khatm.platform.rbac.domain.AppUser;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;

/**
 * Spec FS-0.6b DoD #2 — every login failure reason returns the identical generic {@code
 * KH-RBC-0401} (D7); five failures trip the Redis-TTL lockout counter (D6), after which even the
 * <em>correct</em> password is rejected within the window; once the window elapses, login works
 * again with no administrative action.
 *
 * <p>Uses a dedicated user created directly via the repository (not the shared bootstrap admin
 * {@link RbacHttpTestSupport} tests use), so repeatedly tripping this account's lockout counter
 * never interferes with any other test in this suite.
 */
class AuthLockoutTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CORRECT_PASSWORD = "lockout-test-correct-password";

  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;

  private String username;

  @BeforeEach
  void createTestUser() {
    username = "lockout-probe-" + UUID.randomUUID();
    AppUser user = new AppUser();
    user.setId(Uuidv7.generate());
    user.setTenantId(TenantContext.current());
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(CORRECT_PASSWORD));
    user.setDisplayNameI18n(new LocalizedText("Lockout Probe", "فحص القفل"));
    user.setPreferredLang("en");
    user.setStatus("ACTIVE");
    user.setCreatedAt(Instant.now());
    users.save(user);
  }

  @Test
  void fiveFailures_thenSixthAttemptEvenWithCorrectPassword_isLocked_thenTtlExpiryRecovers()
      throws Exception {
    for (int attempt = 1; attempt <= 5; attempt++) {
      ResponseEntity<String> response = login(username, "wrong-password-" + attempt);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      JsonNode body = JSON.readTree(response.getBody());
      assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0401");
      assertThat(body.get("message").asText())
          .as("D7 — generic message, never reveals the real reason")
          .doesNotContainIgnoringCase("wrong")
          .doesNotContainIgnoringCase("password")
          .doesNotContainIgnoringCase("lock");
    }

    // The 6th attempt, even with the CORRECT password, must still be rejected — the lockout
    // state overrides.
    ResponseEntity<String> lockedAttempt = login(username, CORRECT_PASSWORD);
    assertThat(lockedAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    Integer failedCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'AUTH_LOGIN_FAILED'"
                + " AND entity_ref = ?",
            Integer.class,
            username);
    assertThat(failedCount).isEqualTo(5);
    Integer lockoutCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'AUTH_LOCKOUT_TRIGGERED'"
                + " AND entity_ref = ?",
            Integer.class,
            username);
    assertThat(lockoutCount).isEqualTo(1);

    // After the (short, test-configured) TTL window elapses, the correct password works again —
    // with no administrative unlock action.
    Thread.sleep(2500);
    ResponseEntity<Void> recovered =
        rest.postForEntity(
            "/api/auth/login",
            Map.of("username", username, "password", CORRECT_PASSWORD),
            Void.class);
    assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private ResponseEntity<String> login(String user, String password) {
    return rest.postForEntity(
        "/api/auth/login", Map.of("username", user, "password", password), String.class);
  }
}
