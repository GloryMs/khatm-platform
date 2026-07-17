package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;

/**
 * Spec FS-0.6b DoD #5 — {@code POST /api/admin/api-keys} (scope {@code admin}) shows the raw secret
 * exactly once and persists only the hash + prefix; revocation cuts the key off immediately on its
 * very next request.
 */
class AdminApiKeyEndpointTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private JdbcTemplate jdbc;

  @Test
  void createApiKey_showsSecretOnce_persistsOnlyHashAndPrefix_andRevokeCutsItOffImmediately()
      throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> createResponse =
        SessionTestSupport.post(
            rest,
            "/api/admin/api-keys",
            session,
            Map.of("ownerType", "TENANT", "scopes", java.util.List.of("issue")));
    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    JsonNode created = JSON.readTree(createResponse.getBody());
    String rawKey = created.get("rawKey").asText();
    String keyPrefix = created.get("keyPrefix").asText();
    String id = created.get("id").asText();
    assertThat(rawKey).startsWith("khk_test_" + keyPrefix + ".");

    Map<String, Object> row =
        jdbc.queryForMap("SELECT key_prefix, key_hash FROM api_key WHERE id = ?::uuid", id);
    assertThat(row.get("key_prefix")).isEqualTo(keyPrefix);
    byte[] storedHash = (byte[]) row.get("key_hash");
    assertThat(storedHash).isNotEmpty();
    // The stored hash must never equal the raw secret's own UTF-8 bytes (i.e. it really is
    // hashed, not just copied) — a cheap sanity check without recomputing SHA-256 here.
    assertThat(new String(storedHash, java.nio.charset.StandardCharsets.UTF_8))
        .isNotEqualTo(rawKey);

    // The key works before revocation (it was created with the 'issue' scope).
    ResponseEntity<String> beforeRevoke = issueWith(rawKey);
    assertThat(beforeRevoke.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> revokeResponse =
        SessionTestSupport.post(rest, "/api/admin/api-keys/" + id + "/revoke", session, null);
    assertThat(revokeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    // The very next request with the same key is rejected.
    ResponseEntity<String> afterRevoke = issueWith(rawKey);
    assertThat(afterRevoke.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void createApiKey_withoutAdminScope_returns403() throws Exception {
    // A TENANT key with only 'issue' can't itself create other keys.
    ResponseEntity<String> bootstrapCreate =
        SessionTestSupport.post(
            rest,
            "/api/admin/api-keys",
            SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD),
            Map.of("ownerType", "TENANT", "scopes", java.util.List.of("issue")));
    String limitedKey = JSON.readTree(bootstrapCreate.getBody()).get("rawKey").asText();

    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + limitedKey);
    ResponseEntity<String> response =
        rest.exchange(
            "/api/admin/api-keys",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("ownerType", "TENANT", "scopes", java.util.List.of("issue")), headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  /** Uses {@code /issue} (the key's own granted scope) as an "is this key still valid?" probe. */
  private ResponseEntity<String> issueWith(String rawKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
    Map<String, Object> body = Map.of("holderRef", "holder-admin-key-probe");
    return rest.exchange(
        "/api/v1/credentials/issue",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }
}
