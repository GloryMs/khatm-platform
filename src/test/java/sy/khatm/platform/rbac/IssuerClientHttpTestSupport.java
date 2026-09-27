package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.schema.api.ClaimFieldRequest;
import sy.khatm.platform.schema.api.SchemaCreateRequest;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.schema.domain.SchemaAuthoringService;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.support.HolderRefs;

/**
 * Shared scaffolding for the KH-2.8.1 issuer-client HTTP suites: a controllable {@link Clock} (the
 * only way to test rotation windows and {@code expires_at} without sleeping), a bootstrap console
 * session, and one-liners for creating clients, schemas, and machine-to-machine calls.
 */
@Import(IssuerClientHttpTestSupport.ClockConfig.class)
abstract class IssuerClientHttpTestSupport extends RbacHttpTestSupport {

  static final ObjectMapper JSON = new ObjectMapper();
  static final String CLIENTS = "/api/v1/issuer-clients";

  /** A {@link Clock} tests can move forward; everything else about it is the real time. */
  static final class MutableClock extends Clock {
    private volatile Instant now = Instant.now();

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void reset() {
      now = Instant.now();
    }

    void advance(Duration by) {
      now = now.plus(by);
    }
  }

  @TestConfiguration
  static class ClockConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  /** A created client's one-time material as returned by the API. */
  record ClientKey(String id, String prefix, String apiKey, String holderSecret) {}

  @Autowired protected MutableClock clock;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected SchemaAuthoringService authoring;

  @org.junit.jupiter.api.BeforeEach
  void resetClock() {
    clock.reset();
  }

  protected AuthenticatedSession adminSession() {
    return SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
  }

  protected static JsonNode tree(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      throw new AssertionError("Unparseable body: " + body, e);
    }
  }

  protected static Map<String, Object> nameBody() {
    return Map.of("en", "Test Connector", "ar", "موصِّل اختبار");
  }

  /** Creates a client in the caller's tenant via the console API and returns its one-time key. */
  protected ClientKey createClient(AuthenticatedSession session, List<String> allowedSchemaIds) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", nameBody());
    body.put("allowedSchemaIds", allowedSchemaIds);
    ResponseEntity<String> response = SessionTestSupport.post(rest, CLIENTS, session, body);
    assertThat(response.getStatusCode())
        .as("create client: %s", response.getBody())
        .isEqualTo(HttpStatus.CREATED);
    return readCreated(response.getBody());
  }

  protected static ClientKey readCreated(String body) {
    JsonNode json = tree(body);
    return new ClientKey(
        json.get("id").asText(),
        json.get("keyPrefix").asText(),
        json.get("apiKey").asText(),
        json.hasNonNull("holderHmacSecret") ? json.get("holderHmacSecret").asText() : null);
  }

  /** Creates and publishes a schema in the DEFAULT tenant; returns its id. */
  protected String publishedSchemaInDefaultTenant(String codePrefix) {
    return publishedSchema(codePrefix);
  }

  /** A published schema's id and code. */
  record PublishedSchema(String id, String code) {}

  /** Creates and publishes a schema in whichever tenant {@link TenantContext} currently names. */
  protected String publishedSchema(String codePrefix) {
    return publishedSchemaWithCode(codePrefix).id();
  }

  protected PublishedSchema publishedSchemaWithCode(String codePrefix) {
    String code = codePrefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "/v1";
    SchemaDetail draft =
        authoring.create(
            new SchemaCreateRequest(
                code,
                Map.of("en", "M2M test schema", "ar", "مخطط اختبار"),
                List.of(
                    new ClaimFieldRequest(
                        "field", "text", Map.of("en", "Field", "ar", "حقل"), null)),
                List.of(),
                1,
                null,
                null));
    authoring.publish(draft.id());
    return new PublishedSchema(draft.id().toString(), code);
  }

  /** Same, but in {@code tenantId}/{@code slug}. */
  protected String publishedSchemaIn(UUID tenantId, String slug, String codePrefix) {
    TenantContext.set(tenantId, slug);
    try {
      return publishedSchema(codePrefix);
    } finally {
      TenantContext.clear();
    }
  }

  protected static Map<String, Object> issueBody(String schemaId, String holderRef) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaId", schemaId);
    body.put("holderRef", holderRef);
    body.put("maxUses", 1);
    body.put("claims", Map.of("field", "value"));
    return body;
  }

  protected static String validHolderRef() {
    return HolderRefs.unique("m2m-holder-");
  }

  /** A machine-to-machine call: {@code Authorization: Bearer <rawKey>}, JSON body when given. */
  protected ResponseEntity<String> m2m(HttpMethod method, String path, String rawKey, Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }

  protected ResponseEntity<String> m2mIssue(String rawKey, Map<String, Object> body) {
    return m2m(HttpMethod.POST, "/api/v1/credentials/issue", rawKey, body);
  }

  /** The error envelope with the two per-request fields removed, for byte-for-byte comparison. */
  protected static JsonNode stableEnvelope(String body) {
    com.fasterxml.jackson.databind.node.ObjectNode node =
        (com.fasterxml.jackson.databind.node.ObjectNode) tree(body);
    node.remove("traceId");
    node.remove("timestamp");
    return node;
  }

  record Org(UUID id, String slug) {}

  /** Onboards a fresh tenant through the real platform:admin plane. */
  protected Org onboardTenant(AuthenticatedSession admin, String prefix) {
    String slug = prefix + "-" + UUID.randomUUID();
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            "/api/v1/admin/tenants",
            admin,
            Map.of("slug", slug, "nameI18n", Map.of("en", "x", "ar", "x"), "type", "OTHER"));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    return new Org(UUID.fromString(tree(created.getBody()).get("id").asText()), slug);
  }

  protected int auditCountDefaultTenant(String action, String entityRef) {
    TenantContext.set(TenantContext.DEFAULT_TENANT_ID, TenantContext.DEFAULT_TENANT_SLUG);
    try {
      return jdbc.queryForObject(
          "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_ref = ?",
          Integer.class,
          action,
          entityRef);
    } finally {
      TenantContext.clear();
    }
  }
}
