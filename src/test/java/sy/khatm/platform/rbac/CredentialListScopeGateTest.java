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
import sy.khatm.platform.credential.api.ConsumeRequest;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;

/**
 * KH-1.1.4 — {@code GET /api/v1/credentials} requires a console session specifically ({@code
 * ScopeGuard#requireUserSession}): any operator role works, but no API key of any kind does, even
 * one holding every scope.
 */
class CredentialListScopeGateTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private CredentialService credentialService;

  @Test
  void list_withNoCredential_returns401() {
    ResponseEntity<String> response =
        rest.exchange("/api/v1/credentials", HttpMethod.GET, HttpEntity.EMPTY, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void list_withApiKeyHoldingEveryScope_returns403() throws Exception {
    CreatedApiKey fullKey =
        apiKeyService.create(
            ApiKeyOwnerType.TENANT, null, Set.of("issue", "verify", "consume", "revoke", "admin"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + fullKey.rawKey());

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/credentials", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void list_withConsoleSession_returnsPageEnvelope() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response = SessionTestSupport.get(rest, "/api/v1/credentials", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.has("items")).isTrue();
    assertThat(body.has("totalElements")).isTrue();
  }

  @Test
  void list_withInvalidStatusValue_returns400() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.get(rest, "/api/v1/credentials?status=NOT_A_REAL_STATUS", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-SYS-0400");
  }

  @Test
  void list_withRepeatedStatusQueryParam_bindsAsOrFilter_overRealHttp() throws Exception {
    IssueResponse exhausted =
        credentialService.issue(
            new IssueRequest(
                "CredentialListStatusHttp/v1",
                "holder-status-http-" + UUID.randomUUID(),
                1,
                60,
                Map.of("field", "value"),
                List.of()));
    credentialService.consume(new ConsumeRequest(exhausted.id(), "consumer-http", null));
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> matching =
        SessionTestSupport.get(
            rest,
            "/api/v1/credentials?ref=" + exhausted.ref() + "&status=EXHAUSTED&status=REVOKED",
            session);
    ResponseEntity<String> notMatching =
        SessionTestSupport.get(
            rest, "/api/v1/credentials?ref=" + exhausted.ref() + "&status=ACTIVE", session);

    assertThat(matching.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode matchingBody = JSON.readTree(matching.getBody());
    assertThat(matchingBody.get("totalElements").asInt()).isEqualTo(1);
    assertThat(matchingBody.get("items").get(0).get("ref").asText()).isEqualTo(exhausted.ref());

    assertThat(notMatching.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode notMatchingBody = JSON.readTree(notMatching.getBody());
    assertThat(notMatchingBody.get("totalElements").asInt()).isZero();
  }
}
