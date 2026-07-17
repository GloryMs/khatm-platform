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
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;

/**
 * Spec FS-0.6b DoD #4 — {@code /consume} works with a valid {@code CONSUMING_PARTY} API key and
 * records {@code CREDENTIAL_CONSUMED} via {@code AuditService}; a revoked/malformed key gets {@code
 * KH-RBC-1401} + {@code API_KEY_AUTH_FAILED}; a console session gets {@code 403} — consuming is
 * API-key-only (SEC §7).
 */
class ConsumeApiKeyGateTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void consume_withValidConsumingPartyKey_works_andRecordsAuditRow() throws Exception {
    String issuerRawKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue")).rawKey();
    String credentialId = issueCredential(issuerRawKey);

    CreatedApiKey consumerKey =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, null, Set.of("consume"));

    ResponseEntity<String> response =
        consume(consumerKey.rawKey(), credentialId, "gate-test-consumer");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("consumed").asBoolean()).isTrue();

    Integer auditCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CREDENTIAL_CONSUMED'"
                + " AND entity_ref = ?",
            Integer.class,
            credentialId);
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void consume_withRevokedKey_returns401_andRecordsApiKeyAuthFailed() throws Exception {
    CreatedApiKey key =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, null, Set.of("consume"));
    apiKeyService.revoke(key.id());

    ResponseEntity<String> response =
        consume(key.rawKey(), UUID.randomUUID().toString(), "gate-test-revoked");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-1401");

    Integer auditCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'API_KEY_AUTH_FAILED'"
                + " AND entity_ref = ?",
            Integer.class,
            key.keyPrefix());
    assertThat(auditCount).isGreaterThanOrEqualTo(1);
  }

  @Test
  void consume_withMalformedKey_returns401() throws Exception {
    ResponseEntity<String> response =
        consume("khk_live_not-a-real-key.garbage", UUID.randomUUID().toString(), "gate-test-bad");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-1401");
  }

  @Test
  void consume_withConsoleSession_returns403() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            "/api/v1/credentials/consume",
            session,
            Map.of("id", UUID.randomUUID().toString(), "consumer", "gate-test-session"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  private String issueCredential(String rawApiKey) throws Exception {
    Map<String, Object> issueRequest =
        Map.of(
            "schemaCode",
            "ConsumeGateProbe/v1",
            "holderRef",
            "holder-consume-gate-" + UUID.randomUUID(),
            "claims",
            Map.of("field", "value"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawApiKey);
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/credentials/issue",
            HttpMethod.POST,
            new HttpEntity<>(issueRequest, headers),
            String.class);
    JsonNode body = JSON.readTree(response.getBody());
    return body.get("id").asText();
  }

  private ResponseEntity<String> consume(String rawApiKey, String credentialId, String consumer) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawApiKey);
    Map<String, Object> body = Map.of("id", credentialId, "consumer", consumer);
    return rest.exchange(
        "/api/v1/credentials/consume",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }
}
