package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;

/**
 * KH-1.1.3 — {@code GET /api/v1/stats} requires a console session specifically ({@code
 * ScopeGuard#requireUserSession}), the exact same gate as {@code GET /api/v1/credentials}: any
 * operator role works, no API key of any kind does, even one holding every scope.
 */
class StatsScopeGateTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;

  @Test
  void stats_withNoCredential_returns401() {
    ResponseEntity<String> response =
        rest.exchange("/api/v1/stats", HttpMethod.GET, HttpEntity.EMPTY, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void stats_withApiKeyHoldingEveryScope_returns403() throws Exception {
    CreatedApiKey fullKey =
        apiKeyService.create(
            ApiKeyOwnerType.TENANT,
            null,
            Set.of(
                "issue",
                "verify",
                "consume",
                "revoke",
                "schema:manage",
                "consumer:manage",
                "key:manage",
                "tenant:admin",
                "platform:admin"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + fullKey.rawKey());

    ResponseEntity<String> response =
        rest.exchange("/api/v1/stats", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void stats_withConsoleSession_returnsCountersEnvelope() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response = SessionTestSupport.get(rest, "/api/v1/stats", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.has("window")).isTrue();
    assertThat(body.get("window").has("from")).isTrue();
    assertThat(body.get("window").has("to")).isTrue();
    assertThat(body.has("counters")).isTrue();
    assertThat(body.get("counters").has("issued")).isTrue();
    assertThat(body.get("counters").has("verifyOk")).isTrue();
    assertThat(body.get("counters").has("verifyFailed")).isTrue();
  }

  @Test
  void stats_withExplicitWindow_echoesItBack() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.get(
            rest, "/api/v1/stats?from=2020-01-01T00:00:00Z&to=2020-02-01T00:00:00Z", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("window").get("from").asText()).isEqualTo("2020-01-01T00:00:00Z");
    assertThat(body.get("window").get("to").asText()).isEqualTo("2020-02-01T00:00:00Z");
  }

  @Test
  void stats_withMalformedWindowParam_returns400() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.get(rest, "/api/v1/stats?from=not-a-date", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-SYS-0400");
  }
}
