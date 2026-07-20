package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaDefinition;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.shared.LocalizedText;

/**
 * KH-1.6-early — {@code GET /api/v1/schemas}/{@code /{id}} (console issue-screen dependency): both
 * endpoints require only an authenticated session or API key, no specific scope (the session
 * brief's explicit, documented decision — see {@code rbac.security.SecurityConfig}'s Javadoc).
 */
class SchemaReadEndpointsTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private SchemaCatalog catalog;
  @Autowired private ApiKeyService apiKeyService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void list_withSession_returnsRegisteredSchema() throws Exception {
    SchemaRef schema = ensureProbeSchema("SchemaListProbe/v1", "List Probe");
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response = SessionTestSupport.get(rest, "/api/v1/schemas", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode entries = JSON.readTree(response.getBody());
    assertThat(entries.isArray()).isTrue();
    JsonNode match = findById(entries, schema.id());
    assertThat(match).as("expected schema %s in the list", schema.id()).isNotNull();
    assertThat(match.get("code").asText()).isEqualTo("SchemaListProbe/v1");
    assertThat(match.get("nameI18n").get("en").asText()).isEqualTo("List Probe");
    assertThat(match.get("nameI18n").get("ar").asText()).isEqualTo("فحص القائمة");
    assertThat(match.get("version").asInt()).isEqualTo(1);
    assertThat(match.get("status").asText()).isEqualTo("PUBLISHED");
  }

  @Test
  void get_withSession_returnsDetailIncludingClaimsDef() throws Exception {
    SchemaRef schema = ensureProbeSchema("SchemaDetailProbe/v1", "Detail Probe");
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.get(rest, "/api/v1/schemas/" + schema.id(), session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("id").asText()).isEqualTo(schema.id().toString());
    assertThat(body.get("code").asText()).isEqualTo("SchemaDetailProbe/v1");
    // The claims_def column round-trips through Postgres jsonb, which re-serializes whitespace
    // (e.g. "[]" -> "[ ]"); compare parsed JSON, not the raw string.
    assertThat(JSON.readTree(body.get("claimsDefJson").asText()))
        .isEqualTo(JSON.readTree(schema.claimsDefJson()));
    assertThat(body.get("sdFields").isArray()).isTrue();
    assertThat(body.get("sdFields")).isEmpty();
    assertThat(body.get("defaultMaxUses").asInt()).isEqualTo(1);
    // ensureProbeSchema never sets default_validity — the column stays NULL (no DB default).
    assertThat(body.get("defaultValidity").isNull()).isTrue();
  }

  @Test
  void get_withDefaultValiditySet_returnsIso8601DurationString() throws Exception {
    SchemaRef schema = ensureProbeSchema("SchemaValidityProbe/v1", "Validity Probe");
    // SchemaCatalog has no authoring path for default_validity yet (KH-1.x); set it directly to
    // exercise CredentialSchemaRepository#findDefaultValiditySeconds' EXTRACT(epoch FROM ...)
    // conversion end-to-end, the same direct-JDBC pattern other suites use for columns no service
    // method writes yet.
    jdbc.update(
        "UPDATE credential_schema SET default_validity = ?::interval WHERE id = ?",
        "90 days",
        schema.id());
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.get(rest, "/api/v1/schemas/" + schema.id(), session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("defaultValidity").asText()).isEqualTo("P90D");
  }

  @Test
  void get_unknownId_returns404WithSchemaNotFoundCode() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.get(rest, "/api/v1/schemas/" + UUID.randomUUID(), session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-SCH-0404");
  }

  @Test
  void list_withApiKeyHoldingUnrelatedScope_stillWorks_noSpecificScopeRequired() throws Exception {
    ensureProbeSchema("SchemaApiKeyProbe/v1", "API Key Probe");
    // 'issue' is deliberately unrelated to schema reads — proves no specific scope is enforced.
    String rawKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue")).rawKey();
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);

    ResponseEntity<String> response =
        rest.exchange("/api/v1/schemas", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void list_withNoCredential_returns401() {
    ResponseEntity<String> response =
        rest.exchange("/api/v1/schemas", HttpMethod.GET, HttpEntity.EMPTY, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private SchemaRef ensureProbeSchema(String code, String nameEn) {
    return catalog.ensurePublished(
        new SchemaDefinition(
            code, 1, new LocalizedText(nameEn, "فحص القائمة"), "{\"fields\":[]}", List.of(), 1));
  }

  private static JsonNode findById(JsonNode entries, UUID id) {
    for (JsonNode entry : entries) {
      if (id.toString().equals(entry.get("id").asText())) {
        return entry;
      }
    }
    return null;
  }
}
