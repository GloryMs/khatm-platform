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
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;

/**
 * KH-1.1.3 D6 — {@code POST /api/v1/credentials/verify} records {@code CREDENTIAL_VERIFY_OK}/{@code
 * CREDENTIAL_VERIFY_FAILED} on every call, recorded by {@code CredentialController} after {@code
 * CredentialService#verify} returns (that method's own {@code readOnly = true} transaction cannot
 * accept the write). {@code detail} carries only the reason code — never the presented claim value
 * (P1/SEC §9).
 */
class CredentialVerifyAuditTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void verify_validPresentation_recordsVerifyOk_withRefAndNoClaimContent() throws Exception {
    String secretValue = "TOP-SECRET-VERIFY-AUDIT-" + UUID.randomUUID();
    CreatedApiKey issuerKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));
    IssuedCredential issued = issueCredential(issuerKey.rawKey(), secretValue);

    ResponseEntity<String> response = verify(issued.sdJwt());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(response.getBody()).get("valid").asBoolean()).isTrue();

    Integer okCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CREDENTIAL_VERIFY_OK'"
                + " AND entity_ref = ?",
            Integer.class,
            issued.ref());
    assertThat(okCount).isEqualTo(1);

    String detail =
        jdbc.queryForObject(
            "SELECT detail::text FROM audit_log WHERE action = 'CREDENTIAL_VERIFY_OK'"
                + " AND entity_ref = ?",
            String.class,
            issued.ref());
    assertThat(detail).doesNotContain(secretValue);
  }

  @Test
  void verify_malformedPresentation_recordsVerifyFailed_withNoResolvedRef() throws Exception {
    ResponseEntity<String> response = verify("not-a-valid-sd-jwt-at-all");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(response.getBody()).get("valid").asBoolean()).isFalse();

    Integer failedCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CREDENTIAL_VERIFY_FAILED'"
                + " AND entity_ref IS NULL AND detail::text LIKE '%malformed%'",
            Integer.class);
    assertThat(failedCount).isGreaterThanOrEqualTo(1);
  }

  private IssuedCredential issueCredential(String rawApiKey, String secretValue) throws Exception {
    Map<String, Object> issueRequest =
        Map.of(
            "schemaCode",
            "VerifyAuditProbe/v1",
            "holderRef",
            "holder-verify-audit-" + UUID.randomUUID(),
            "claims",
            Map.of("secretValue", secretValue));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawApiKey);
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/credentials/issue",
            HttpMethod.POST,
            new HttpEntity<>(issueRequest, headers),
            String.class);
    JsonNode body = JSON.readTree(response.getBody());
    return new IssuedCredential(body.get("ref").asText(), body.get("sdJwt").asText());
  }

  private ResponseEntity<String> verify(String sdJwt) {
    return rest.postForEntity("/api/v1/credentials/verify", Map.of("sdJwt", sdJwt), String.class);
  }

  private record IssuedCredential(String ref, String sdJwt) {}
}
