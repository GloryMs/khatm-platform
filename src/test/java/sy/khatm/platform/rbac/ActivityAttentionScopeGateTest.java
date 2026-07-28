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
 * KH-1.1.5-BE, spec FS-1.5.4 — {@code GET /api/v1/activity} and {@code GET /api/v1/attention} use
 * the exact same gate as {@code GET /api/v1/stats} ({@code ScopeGuard#requireUserSession}): a
 * console session works, no API key of any kind does, even one holding every scope.
 */
class ActivityAttentionScopeGateTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;

  @Test
  void activity_withNoCredential_returns401() {
    ResponseEntity<String> response =
        rest.exchange("/api/v1/activity", HttpMethod.GET, HttpEntity.EMPTY, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void activity_withApiKeyHoldingEveryScope_returns403() throws Exception {
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
        rest.exchange("/api/v1/activity", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void activity_withConsoleSession_returnsItemsEnvelope() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response = SessionTestSupport.get(rest, "/api/v1/activity", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.has("items")).isTrue();
    assertThat(body.get("items").isArray()).isTrue();
  }

  @Test
  void attention_withNoCredential_returns401() {
    ResponseEntity<String> response =
        rest.exchange("/api/v1/attention", HttpMethod.GET, HttpEntity.EMPTY, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void attention_withApiKeyHoldingEveryScope_returns403() throws Exception {
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
        rest.exchange("/api/v1/attention", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void attention_withConsoleSession_returnsItemsEnvelope() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response = SessionTestSupport.get(rest, "/api/v1/attention", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.has("items")).isTrue();
    assertThat(body.get("items").isArray()).isTrue();
  }

  @Test
  void statsDaily_withConsoleSession_returnsDaysEnvelope_provingWidenedStatsPathStillGated()
      throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response = SessionTestSupport.get(rest, "/api/v1/stats/daily", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.has("window")).isTrue();
    assertThat(body.has("days")).isTrue();
  }

  @Test
  void statsDaily_withNoCredential_returns401_provingWildcardStillGated() {
    ResponseEntity<String> response =
        rest.exchange("/api/v1/stats/daily", HttpMethod.GET, HttpEntity.EMPTY, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void signingKeys_withNoCredential_returns401() {
    ResponseEntity<String> response =
        rest.exchange("/api/v1/admin/signing-keys", HttpMethod.GET, HttpEntity.EMPTY, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void signingKeys_withKeyManageScopedApiKey_returnsKeysEnvelope() throws Exception {
    CreatedApiKey keyManageKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("key:manage"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + keyManageKey.rawKey());

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/signing-keys", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.has("keys")).isTrue();
    assertThat(body.get("keys").isArray()).isTrue();
  }

  @Test
  void consumingPartyStats_withConsoleSession_returnsPartiesEnvelope() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.get(rest, "/api/v1/stats/consuming-parties", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.has("window")).isTrue();
    assertThat(body.has("parties")).isTrue();
    assertThat(body.get("parties").isArray()).isTrue();
  }

  @Test
  void consumingPartyStats_withNoCredential_returns401() {
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/stats/consuming-parties", HttpMethod.GET, HttpEntity.EMPTY, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void signingKeys_withApiKeyMissingKeyManageScope_returns403() throws Exception {
    CreatedApiKey key = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + key.rawKey());

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/signing-keys", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }
}
