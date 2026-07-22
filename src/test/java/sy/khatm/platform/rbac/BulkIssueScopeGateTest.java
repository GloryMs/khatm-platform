package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.AppUser;
import sy.khatm.platform.rbac.domain.CreatedApiKey;
import sy.khatm.platform.rbac.domain.Role;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.rbac.persistence.RoleRepository;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;

/**
 * KH-1.1.3 — {@code POST /api/v1/credentials/bulk} reuses {@code /issue}'s exact scope rule ({@code
 * ScopeGuard#requireScopeNotConsumingPartyKey}): no session/key at all is 401; a {@code
 * CONSUMING_PARTY} key or a valid session/key missing the {@code issue} scope is 403; a TENANT API
 * key or an {@code ISSUER_OPERATOR} session with {@code issue} works (200, even for a batch that
 * fails wholesale on validation — the gate only cares about auth, not the body).
 */
class BulkIssueScopeGateTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "bulk-issue-gate-password";

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private AppUserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void bulkIssue_withNoSessionOrKey_returns401() {
    ResponseEntity<String> response = bulkIssueRequest(new HttpHeaders());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertCode(response, "KH-RBC-0401");
  }

  @Test
  void bulkIssue_withConsumingPartyKey_returns403() {
    CreatedApiKey consumerKey =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, null, Set.of("issue"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + consumerKey.rawKey());

    ResponseEntity<String> response = bulkIssueRequest(headers);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertCode(response, "KH-RBC-0403");
  }

  @Test
  void bulkIssue_withTenantKeyMissingIssueScope_returns403() {
    CreatedApiKey noScopeKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of());
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + noScopeKey.rawKey());

    ResponseEntity<String> response = bulkIssueRequest(headers);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertCode(response, "KH-RBC-0403");
  }

  @Test
  void bulkIssue_withTenantKeyAndIssueScope_works() throws Exception {
    CreatedApiKey issuerKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + issuerKey.rawKey());

    ResponseEntity<String> response = bulkIssueRequest(headers);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("total").asInt()).isEqualTo(1);
  }

  @Test
  void bulkIssue_withIssuerOperatorSession_works() throws Exception {
    String username = createUser("bulk-issue-operator-" + UUID.randomUUID(), "ISSUER_OPERATOR");
    AuthenticatedSession session = SessionTestSupport.login(rest, username, PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.post(rest, "/api/v1/credentials/bulk", session, bulkIssueBody());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private ResponseEntity<String> bulkIssueRequest(HttpHeaders headers) {
    return rest.exchange(
        "/api/v1/credentials/bulk",
        HttpMethod.POST,
        new HttpEntity<>(bulkIssueBody(), headers),
        String.class);
  }

  private Map<String, Object> bulkIssueBody() {
    return Map.of(
        "schemaCode",
        "BulkGateProbe/v1",
        "items",
        List.of(Map.of("claims", Map.of("field", "value"), "pseudoRef", "holder-bulk-gate")));
  }

  private static void assertCode(ResponseEntity<String> response, String expectedCode) {
    try {
      JsonNode body = JSON.readTree(response.getBody());
      assertThat(body.get("code").asText()).isEqualTo(expectedCode);
    } catch (Exception e) {
      throw new AssertionError("Failed to parse response body", e);
    }
  }

  private String createUser(String username, String roleCode) {
    // See ScopeGateTest's identical helper for why TransactionTemplate (not a plain method, not a
    // self-invoked @Transactional method) is required here.
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
              Role role =
                  roles.findByTenantIdAndCode(TenantContext.current(), roleCode).orElseThrow();
              roles.assignRole(user.getId(), role.getId());
            });
    return username;
  }
}
