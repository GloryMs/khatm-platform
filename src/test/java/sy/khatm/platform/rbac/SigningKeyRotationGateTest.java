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
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;

/**
 * Spec FS-2.3 D2/D4 (KH-2.3a) — HTTP-level scope gate + lifecycle proof for {@code POST
 * /api/v1/admin/signing-keys/rotate} and {@code POST /api/v1/admin/signing-keys/{kid}/retire}, the
 * same {@code key:manage} gate {@code ActivityAttentionScopeGateTest} already pins for the existing
 * {@code GET}.
 */
class SigningKeyRotationGateTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;

  @Test
  void rotate_withNoCredential_returns401() {
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/signing-keys/rotate", HttpMethod.POST, HttpEntity.EMPTY, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rotate_withApiKeyMissingKeyManageScope_returns403() throws Exception {
    CreatedApiKey key = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));
    HttpHeaders headers = keyHeader(key);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/signing-keys/rotate",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void rotate_withKeyManageScopedApiKey_returnsNewActiveKey() throws Exception {
    CreatedApiKey key = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("key:manage"));
    HttpHeaders headers = keyHeader(key);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/signing-keys/rotate",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("state").asText()).isEqualTo("ACTIVE");
    assertThat(body.get("kid").asText()).isNotBlank();
  }

  @Test
  void retire_withNoCredential_returns401() {
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/signing-keys/some-kid/retire",
            HttpMethod.POST,
            HttpEntity.EMPTY,
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void retire_withApiKeyMissingKeyManageScope_returns403() throws Exception {
    CreatedApiKey key = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));
    HttpHeaders headers = keyHeader(key);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/signing-keys/some-kid/retire",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void retire_unknownKid_returns404() throws Exception {
    CreatedApiKey key = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("key:manage"));
    HttpHeaders headers = keyHeader(key);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/signing-keys/nonexistent-tenant:key-999/retire",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-KEY-0404");
  }

  @Test
  void retire_activeKey_returns409() throws Exception {
    CreatedApiKey key = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("key:manage"));
    HttpHeaders headers = keyHeader(key);
    JsonNode statusList = signingKeys(headers);
    String activeKid = firstKeyWithState(statusList, "ACTIVE");

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/signing-keys/" + activeKid + "/retire",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-KEY-0409");
  }

  @Test
  void retire_tooYoungWithoutForce_returns422_thenForceTrue_returns200() throws Exception {
    CreatedApiKey key = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("key:manage"));
    HttpHeaders headers = keyHeader(key);

    ResponseEntity<String> rotateResponse =
        rest.exchange(
            "/api/v1/admin/signing-keys/rotate",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            String.class);
    JsonNode statusList = signingKeys(headers);
    String retiringKid = firstKeyWithState(statusList, "RETIRING");

    HttpHeaders jsonHeaders = keyHeader(key);
    jsonHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    ResponseEntity<String> tooYoung =
        rest.exchange(
            "/api/v1/admin/signing-keys/" + retiringKid + "/retire",
            HttpMethod.POST,
            new HttpEntity<>(jsonHeaders),
            String.class);

    assertThat(tooYoung.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    JsonNode tooYoungBody = JSON.readTree(tooYoung.getBody());
    assertThat(tooYoungBody.get("code").asText()).isEqualTo("KH-KEY-0422");

    ResponseEntity<String> forced =
        rest.exchange(
            "/api/v1/admin/signing-keys/" + retiringKid + "/retire",
            HttpMethod.POST,
            new HttpEntity<>("{\"force\":true}", jsonHeaders),
            String.class);

    assertThat(forced.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode forcedBody = JSON.readTree(forced.getBody());
    assertThat(forcedBody.get("state").asText()).isEqualTo("RETIRED");
    assertThat(rotateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private static HttpHeaders keyHeader(CreatedApiKey key) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + key.rawKey());
    return headers;
  }

  private JsonNode signingKeys(HttpHeaders headers) throws Exception {
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/signing-keys", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    return JSON.readTree(response.getBody());
  }

  private static String firstKeyWithState(JsonNode signingKeysResponse, String state) {
    for (JsonNode key : signingKeysResponse.get("keys")) {
      if (state.equals(key.get("state").asText())) {
        return key.get("kid").asText();
      }
    }
    throw new IllegalStateException(
        "No key with state " + state + " found: " + signingKeysResponse);
  }
}
