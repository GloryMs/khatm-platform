package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
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
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.tenant.api.TenantAdmin;
import sy.khatm.platform.tenant.api.TenantView;

/**
 * Spec FS-0.6b DoD #5, re-gated KH-2.2a (spec FS-2.2 D2/V4/D4) — {@code POST
 * /api/v1/admin/api-keys} (scope {@code tenant:admin}) shows the raw secret exactly once and
 * persists only the hash + prefix; revocation cuts the key off immediately on its very next
 * request. The explicit-{@code tenantId} cross-tenant path additionally requires {@code
 * platform:admin} (enforced by {@code shared.OnBehalfOfExecutor}), proven separately below.
 */
class AdminApiKeyEndpointTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApiKeyService apiKeyService;
  @Autowired private TenantAdmin tenantAdmin;

  @Test
  void createApiKey_showsSecretOnce_persistsOnlyHashAndPrefix_andRevokeCutsItOffImmediately()
      throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> createResponse =
        SessionTestSupport.post(
            rest,
            "/api/v1/admin/api-keys",
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
        SessionTestSupport.post(rest, "/api/v1/admin/api-keys/" + id + "/revoke", session, null);
    assertThat(revokeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    // The very next request with the same key is rejected.
    ResponseEntity<String> afterRevoke = issueWith(rawKey);
    assertThat(afterRevoke.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void createApiKey_withoutTenantAdminScope_returns403() throws Exception {
    // A TENANT key with only 'issue' can't itself create other keys.
    ResponseEntity<String> bootstrapCreate =
        SessionTestSupport.post(
            rest,
            "/api/v1/admin/api-keys",
            SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD),
            Map.of("ownerType", "TENANT", "scopes", java.util.List.of("issue")));
    String limitedKey = JSON.readTree(bootstrapCreate.getBody()).get("rawKey").asText();

    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + limitedKey);
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/api-keys",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("ownerType", "TENANT", "scopes", java.util.List.of("issue")), headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void createApiKey_selfService_withOnlyTenantAdminScope_succeeds() throws Exception {
    // Spec V4 baseline: tenant:admin alone is enough for a TENANT key's self-service (no tenantId,
    // or tenantId == caller's own ambient tenant) — no platform:admin, no OnBehalfOfExecutor.
    CreatedApiKey tenantAdminKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("tenant:admin"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + tenantAdminKey.rawKey());

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/api-keys",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("ownerType", "TENANT", "scopes", Set.of("issue")), headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void createApiKey_forAnotherTenant_withOnlyTenantAdminScope_returns403_andCreatesNoRow()
      throws Exception {
    // Spec FS-2.2 D4 — the cross-tenant on-behalf-of guard this session closes: naming a foreign
    // tenantId with only tenant:admin (not platform:admin) must be rejected, not silently minted
    // under the target tenant. Before OnBehalfOfExecutor this was a real gap (any 'admin'-scope
    // caller could mint a key for any tenant it named).
    TenantView otherTenant =
        tenantAdmin.create(
            uniqueSlug("cross-tenant-keys"),
            new sy.khatm.platform.shared.LocalizedText("x", "x"),
            "OTHER",
            null);
    CreatedApiKey tenantAdminKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("tenant:admin"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + tenantAdminKey.rawKey());

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/api-keys",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "ownerType",
                    "TENANT",
                    "scopes",
                    Set.of("issue"),
                    "tenantId",
                    otherTenant.id().toString()),
                headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");

    // api_key is RLS-protected, and this verification query runs on the test's own thread — no
    // TenantContext was ever set on it, so it falls back to the default tenant, never otherTenant.
    // A bare COUNT(*) would pass vacuously even if a row HAD been created (RLS would just hide it),
    // so this must run under otherTenant's own context to be a genuine proof of absence.
    TenantContext.set(otherTenant.id(), otherTenant.slug());
    try {
      Integer keyCount =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM api_key WHERE tenant_id = ?::uuid",
              Integer.class,
              otherTenant.id().toString());
      assertThat(keyCount).isZero();
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void createApiKey_forAnotherTenant_withPlatformAdminScope_succeeds_andAuditsOnBehalfOf()
      throws Exception {
    TenantView otherTenant =
        tenantAdmin.create(
            uniqueSlug("cross-tenant-keys-ok"),
            new sy.khatm.platform.shared.LocalizedText("x", "x"),
            "OTHER",
            null);
    CreatedApiKey platformAdminKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("platform:admin"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + platformAdminKey.rawKey());

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/admin/api-keys",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "ownerType",
                    "TENANT",
                    "scopes",
                    Set.of("issue"),
                    "tenantId",
                    otherTenant.id().toString()),
                headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    String id = JSON.readTree(response.getBody()).get("id").asText();

    // api_key is RLS-protected and the created row lives under otherTenant, not the test thread's
    // own default-tenant fallback — same reasoning as the 403 test above.
    TenantContext.set(otherTenant.id(), otherTenant.slug());
    try {
      Map<String, Object> row =
          jdbc.queryForMap("SELECT tenant_id FROM api_key WHERE id = ?::uuid", id);
      assertThat(row.get("tenant_id").toString()).isEqualTo(otherTenant.id().toString());
    } finally {
      TenantContext.clear();
    }

    // shared.OnBehalfOfExecutor writes the ON_BEHALF_OF row BEFORE switching TenantContext — under
    // the calling platform-admin key's own tenant (the default tenant here, spec D4's own wording:
    // "the calling admin's own ambient tenant"), not otherTenant. The test thread's own no-context
    // fallback already resolves to that same default tenant, so no explicit TenantContext.set here.
    Integer onBehalfOfCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'ON_BEHALF_OF' AND entity_ref = ?",
            Integer.class,
            otherTenant.slug());
    assertThat(onBehalfOfCount).isEqualTo(1);
  }

  private static String uniqueSlug(String prefix) {
    return prefix + "-" + java.util.UUID.randomUUID();
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
