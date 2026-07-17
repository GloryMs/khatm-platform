package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.AppUser;
import sy.khatm.platform.rbac.domain.Role;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.rbac.persistence.RoleRepository;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;

/**
 * Spec FS-0.6b DoD #3 — {@code /issue} without any session or key returns 401; with a session
 * lacking the {@code issue} scope returns 403; with an {@code ISSUER_OPERATOR} session, it works
 * exactly as before KH-0.6b.
 */
class ScopeGateTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "scope-gate-test-password";

  @Autowired private AppUserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void issue_withNoSessionOrKey_returns401() throws Exception {
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/credentials/issue", Map.of("holderRef", "holder-no-auth"), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0401");
  }

  @Test
  void issue_withSessionLackingIssueScope_returns403() throws Exception {
    String username = createUser("no-scope-" + UUID.randomUUID(), null);
    AuthenticatedSession session = SessionTestSupport.login(rest, username, PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest, "/api/v1/credentials/issue", session, Map.of("holderRef", "holder-no-scope"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void issue_withIssuerOperatorSession_works() {
    String username = createUser("operator-" + UUID.randomUUID(), "ISSUER_OPERATOR");
    AuthenticatedSession session = SessionTestSupport.login(rest, username, PASSWORD);

    Map<String, Object> issueRequest =
        Map.of(
            "schemaCode", "ScopeGateProbe/v1",
            "holderRef", "holder-scope-gate-operator",
            "claims", Map.of("field", "value"));

    ResponseEntity<String> response =
        SessionTestSupport.post(rest, "/api/v1/credentials/issue", session, issueRequest);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private String createUser(String username, String roleCode) {
    // roles.assignRole is a @Modifying query — it requires an active transaction, which a plain
    // test helper method doesn't have on its own (and a self-invoked @Transactional method
    // wouldn't help either, since that bypasses the Spring proxy). TransactionTemplate gives it
    // one explicitly, committing before this method returns.
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              AppUser user = new AppUser();
              user.setId(Uuidv7.generate());
              user.setTenantId(TenantContext.current());
              user.setUsername(username);
              user.setPasswordHash(passwordEncoder.encode(PASSWORD));
              user.setDisplayNameI18n(new LocalizedText(username, username));
              user.setPreferredLang("en");
              user.setStatus("ACTIVE");
              user.setCreatedAt(Instant.now());
              users.save(user);
              if (roleCode != null) {
                Role role =
                    roles.findByTenantIdAndCode(TenantContext.current(), roleCode).orElseThrow();
                roles.assignRole(user.getId(), role.getId());
              }
            });
    return username;
  }
}
