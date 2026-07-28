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
import sy.khatm.platform.consumer.api.ConsumingPartyRef;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;

/**
 * KH-2.1-BE Part A, re-gated KH-2.2a (spec FS-2.2 D2) — the tenant admin/onboarding plane over real
 * HTTP: the {@code platform:admin}-exclusive gate on every endpoint (not even {@code tenant:admin}
 * suffices, per D2's own wording), a full onboard → list → get → suspend → activate lifecycle walk
 * with audit-row assertions (including the on-behalf-of audit row {@code create} now writes via
 * {@code shared.OnBehalfOfExecutor}), the D9 duplicate-slug 409, and invalid-slug 400. Domain-level
 * behaviour is covered in more detail by {@code tenant.domain.TenantAdminServiceTest}; this class
 * proves the endpoints, status codes, error envelopes, and gate wire up correctly.
 */
class TenantAdminGateTest extends RbacHttpTestSupport {

  private static final String BASE = "/api/v1/admin/tenants";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private ConsumingPartyRegistry consumingParties;
  @Autowired private JdbcTemplate jdbc;

  private static String uniqueSlug(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private static Map<String, Object> createBody(String slug) {
    return Map.of(
        "slug", slug, "nameI18n", Map.of("en", "Acme", "ar", "أكمي"), "type", "GOVERNMENT");
  }

  private int auditCount(String action, String slug) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_ref = ?",
        Integer.class,
        action,
        slug);
  }

  // ── Scope gate ────────────────────────────────────────────────────────────────────────────

  @Test
  void list_withNoCredential_returns401() {
    ResponseEntity<String> response = rest.getForEntity(BASE, String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void create_withConsumingPartyKey_returns403() throws Exception {
    ConsumingPartyRef party = consumingParties.ensure(uniqueSlug("gate-cp-owner"));
    CreatedApiKey cpKey =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, party.id(), Set.of("consume"));

    ResponseEntity<String> response = createWithApiKey(cpKey.rawKey(), uniqueSlug("gate-cp"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void create_withTenantKeyMissingPlatformAdminScope_returns403() throws Exception {
    CreatedApiKey issuerKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));

    ResponseEntity<String> response =
        createWithApiKey(issuerKey.rawKey(), uniqueSlug("gate-noscope"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void create_withTenantAdminScopeButNotPlatformAdmin_returns403() throws Exception {
    // D2's own wording: platform:admin exclusively — tenant:admin (the scope that gates every
    // OTHER tenant-facing admin surface) must NOT be enough here, since /admin/tenants is the one
    // cross-tenant plane on the platform.
    CreatedApiKey tenantAdminKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("tenant:admin"));

    ResponseEntity<String> response =
        createWithApiKey(tenantAdminKey.rawKey(), uniqueSlug("gate-tenantadmin-notenough"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void create_withPlatformAdminApiKey_succeeds() throws Exception {
    CreatedApiKey platformAdminKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("platform:admin"));

    ResponseEntity<String> response =
        createWithApiKey(platformAdminKey.rawKey(), uniqueSlug("gate-platformadminkey"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(response.getBody()).get("status").asText()).isEqualTo("ACTIVE");
  }

  // ── Full lifecycle (admin session) ──────────────────────────────────────────────────────────

  @Test
  void fullLifecycle_createGetListSuspendActivate_withAuditRows() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String slug = uniqueSlug("gate-lifecycle");

    ResponseEntity<String> created = SessionTestSupport.post(rest, BASE, session, createBody(slug));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode createdBody = JSON.readTree(created.getBody());
    String id = createdBody.get("id").asText();
    assertThat(createdBody.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(auditCount("TENANT_CREATED", slug)).isEqualTo(1);

    ResponseEntity<String> fetched = SessionTestSupport.get(rest, BASE + "/" + id, session);
    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(fetched.getBody()).get("slug").asText()).isEqualTo(slug);

    ResponseEntity<String> listed = SessionTestSupport.get(rest, BASE, session);
    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(listed.getBody()).contains(slug);

    ResponseEntity<String> suspended =
        SessionTestSupport.post(rest, BASE + "/" + id + "/suspend", session, null);
    assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(suspended.getBody()).get("status").asText()).isEqualTo("SUSPENDED");
    assertThat(auditCount("TENANT_SUSPENDED", slug)).isEqualTo(1);

    ResponseEntity<String> activated =
        SessionTestSupport.post(rest, BASE + "/" + id + "/activate", session, null);
    assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(activated.getBody()).get("status").asText()).isEqualTo("ACTIVE");
    assertThat(auditCount("TENANT_ACTIVATED", slug)).isEqualTo(1);
  }

  @Test
  void create_duplicateSlug_returns409_andLeavesOneRow() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String slug = uniqueSlug("gate-dup");

    ResponseEntity<String> first = SessionTestSupport.post(rest, BASE, session, createBody(slug));
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> second = SessionTestSupport.post(rest, BASE, session, createBody(slug));
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(JSON.readTree(second.getBody()).get("code").asText()).isEqualTo("KH-TNT-0409");

    Integer rows =
        jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE slug = ?", Integer.class, slug);
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void create_invalidSlug_returns400() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            BASE,
            session,
            Map.of(
                "slug",
                "Bad Slug!",
                "nameI18n",
                Map.of("en", "x", "ar", "x"),
                "type",
                "GOVERNMENT"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-TNT-0400");
  }

  @Test
  void get_unknownTenant_returns404() {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.get(rest, BASE + "/" + UUID.randomUUID(), session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private ResponseEntity<String> createWithApiKey(String rawKey, String slug) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
    return rest.exchange(
        BASE, HttpMethod.POST, new HttpEntity<>(createBody(slug), headers), String.class);
  }
}
