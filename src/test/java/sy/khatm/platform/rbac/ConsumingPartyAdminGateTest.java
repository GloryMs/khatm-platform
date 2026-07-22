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
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.consumer.api.ConsumingPartyRef;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaDefinition;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.shared.LocalizedText;

/**
 * KH-1.4.4 — the consuming-party admin plane over real HTTP: the {@code admin}-scope gate on every
 * endpoint, a full create → list → suspend → activate → allow → disallow lifecycle walk with
 * audit-row assertions, the D2 duplicate-code 409, the D5 referential 404s, invalid-code 400, and
 * the key-mint endpoint returning a one-time raw key. Domain-level behaviour is covered in more
 * detail by {@code consumer.domain.ConsumingPartyAdminServiceTest}; this class proves the
 * endpoints, status codes, error envelopes, and gate wire up correctly.
 */
class ConsumingPartyAdminGateTest extends RbacHttpTestSupport {

  private static final String BASE = "/api/v1/admin/consuming-parties";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private ConsumingPartyRegistry consumingParties;
  @Autowired private SchemaCatalog schemaCatalog;
  @Autowired private JdbcTemplate jdbc;

  private static String uniqueCode(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private UUID ensureSchema(String code) {
    SchemaRef ref =
        schemaCatalog.ensurePublished(
            new SchemaDefinition(code, 1, new LocalizedText(code, code), "{}", List.of(), 1));
    return ref.id();
  }

  private static Map<String, Object> createBody(String code) {
    return Map.of("code", code, "nameI18n", Map.of("en", "Verifier", "ar", "المدقق"));
  }

  private int auditCount(String action, String code) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_ref = ?",
        Integer.class,
        action,
        code);
  }

  // ── Scope gate ────────────────────────────────────────────────────────────────────────────

  @Test
  void list_withNoCredential_returns401() {
    ResponseEntity<String> response = rest.getForEntity(BASE, String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void create_withConsumingPartyKey_returns403() throws Exception {
    ConsumingPartyRef party = consumingParties.ensure(uniqueCode("gate-cp-owner"));
    CreatedApiKey cpKey =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, party.id(), Set.of("consume"));

    ResponseEntity<String> response = createWithApiKey(cpKey.rawKey(), uniqueCode("gate-cp"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void create_withTenantKeyMissingAdminScope_returns403() throws Exception {
    CreatedApiKey issuerKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));

    ResponseEntity<String> response =
        createWithApiKey(issuerKey.rawKey(), uniqueCode("gate-noscope"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void create_withAdminApiKey_succeeds() throws Exception {
    CreatedApiKey adminKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("admin"));

    ResponseEntity<String> response =
        createWithApiKey(adminKey.rawKey(), uniqueCode("gate-adminkey"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(response.getBody()).get("status").asText()).isEqualTo("ACTIVE");
  }

  // ── Full lifecycle (admin session) ──────────────────────────────────────────────────────────

  @Test
  void fullLifecycle_createListSuspendActivateAllowDisallow_withAuditRows() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String code = uniqueCode("gate-lifecycle");
    UUID schemaId = ensureSchema(uniqueCode("GateLifecycleSchema") + "/v1");

    ResponseEntity<String> created = SessionTestSupport.post(rest, BASE, session, createBody(code));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode createdBody = JSON.readTree(created.getBody());
    String id = createdBody.get("id").asText();
    assertThat(createdBody.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(auditCount("CONSUMING_PARTY_CREATED", code)).isEqualTo(1);

    ResponseEntity<String> listed = SessionTestSupport.get(rest, BASE, session);
    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(containsId(listed.getBody(), id)).isTrue();

    ResponseEntity<String> suspended =
        SessionTestSupport.post(rest, BASE + "/" + id + "/suspend", session, null);
    assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(suspended.getBody()).get("status").asText()).isEqualTo("SUSPENDED");
    assertThat(auditCount("CONSUMING_PARTY_SUSPENDED", code)).isEqualTo(1);

    ResponseEntity<String> activated =
        SessionTestSupport.post(rest, BASE + "/" + id + "/activate", session, null);
    assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(activated.getBody()).get("status").asText()).isEqualTo("ACTIVE");
    assertThat(auditCount("CONSUMING_PARTY_ACTIVATED", code)).isEqualTo(1);

    ResponseEntity<String> allowed =
        SessionTestSupport.post(
            rest, BASE + "/" + id + "/allowed-schemas", session, Map.of("schemaId", schemaId));
    assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode allowedSchemas = JSON.readTree(allowed.getBody()).get("allowedSchemas");
    assertThat(allowedSchemas).hasSize(1);
    assertThat(allowedSchemas.get(0).get("schemaId").asText()).isEqualTo(schemaId.toString());
    assertThat(auditCount("CONSUMING_PARTY_SCHEMA_ALLOWED", code)).isEqualTo(1);

    ResponseEntity<String> disallowed =
        rest.exchange(
            BASE + "/" + id + "/allowed-schemas/" + schemaId,
            HttpMethod.DELETE,
            session.writeHeaders(),
            String.class);
    assertThat(disallowed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(auditCount("CONSUMING_PARTY_SCHEMA_DISALLOWED", code)).isEqualTo(1);

    // Re-fetch and confirm the allowlist is now empty for this party.
    JsonNode party = findById(SessionTestSupport.get(rest, BASE, session).getBody(), id);
    assertThat(party.get("allowedSchemas")).isEmpty();
  }

  // ── D2 idempotency ──────────────────────────────────────────────────────────────────────────

  @Test
  void create_duplicateCode_returns409_andLeavesOneRow() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String code = uniqueCode("gate-dup");

    ResponseEntity<String> first = SessionTestSupport.post(rest, BASE, session, createBody(code));
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> second = SessionTestSupport.post(rest, BASE, session, createBody(code));
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(JSON.readTree(second.getBody()).get("code").asText()).isEqualTo("KH-CNS-0409");

    Integer rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM consuming_party WHERE code = ?", Integer.class, code);
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void create_invalidCode_returns400() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            BASE,
            session,
            Map.of("code", "Bad Code!", "nameI18n", Map.of("en", "x", "ar", "x")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-CNS-0400");
  }

  // ── D5 referential ──────────────────────────────────────────────────────────────────────────

  @Test
  void allow_unknownParty_returns404() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    UUID schemaId = ensureSchema(uniqueCode("GateAllowNoParty") + "/v1");

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            BASE + "/" + UUID.randomUUID() + "/allowed-schemas",
            session,
            Map.of("schemaId", schemaId));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-CNS-0404");
  }

  @Test
  void allow_unknownSchema_returns404() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String code = uniqueCode("gate-allownoschema");
    String id =
        JSON.readTree(SessionTestSupport.post(rest, BASE, session, createBody(code)).getBody())
            .get("id")
            .asText();

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            BASE + "/" + id + "/allowed-schemas",
            session,
            Map.of("schemaId", UUID.randomUUID()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-CNS-1404");
  }

  @Test
  void disallow_nonAllowedPair_returns204() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String code = uniqueCode("gate-disallow-noop");
    String id =
        JSON.readTree(SessionTestSupport.post(rest, BASE, session, createBody(code)).getBody())
            .get("id")
            .asText();

    ResponseEntity<String> response =
        rest.exchange(
            BASE + "/" + id + "/allowed-schemas/" + UUID.randomUUID(),
            HttpMethod.DELETE,
            session.writeHeaders(),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  // ── Key mint ──────────────────────────────────────────────────────────────────────────────

  @Test
  void mintKey_returnsOneTimeRawKey() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String code = uniqueCode("gate-mint");
    String id =
        JSON.readTree(SessionTestSupport.post(rest, BASE, session, createBody(code)).getBody())
            .get("id")
            .asText();

    ResponseEntity<String> response =
        SessionTestSupport.post(rest, BASE + "/" + id + "/api-keys", session, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("rawKey").asText()).startsWith("khk_");
    assertThat(body.get("keyPrefix").asText()).isNotBlank();
  }

  @Test
  void mintKey_forUnknownParty_returns404() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.post(rest, BASE + "/" + UUID.randomUUID() + "/api-keys", session, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("KH-CNS-0404");
  }

  private ResponseEntity<String> createWithApiKey(String rawKey, String code) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
    return rest.exchange(
        BASE, HttpMethod.POST, new HttpEntity<>(createBody(code), headers), String.class);
  }

  private static boolean containsId(String listJson, String id) throws Exception {
    return findById(listJson, id) != null;
  }

  private static JsonNode findById(String listJson, String id) throws Exception {
    for (JsonNode entry : JSON.readTree(listJson)) {
      if (id.equals(entry.get("id").asText())) {
        return entry;
      }
    }
    return null;
  }
}
