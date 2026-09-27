package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.shared.TenantContext;

/**
 * KH-2.8.1 (spec FS-2.7a D2/D3/D4/D8/D11/D12) — a machine-to-machine issuer client over real HTTP:
 * what it may call (one central deny-by-default rule, proved against EVERY registered route), what
 * it may see (its own credentials only), tenant isolation, indistinguishable failures with
 * distinguishable audit rows, throttled failure auditing, the connector contract on issuance, and
 * the no-secrets-in-logs rule.
 */
class IssuerClientAuthHttpTest extends IssuerClientHttpTestSupport {

  private static final String ISSUE = "/api/v1/credentials/issue";
  private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  // ── D3/D6 — issuing, attribution ────────────────────────────────────────────────────────────

  @Test
  void issue_withValidKey_stampsTheClientOnTheCredential_andAuditsAsApiKey() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("m2m-issue");
    ClientKey client = createClient(admin, List.of(schemaId));

    ResponseEntity<String> issued =
        m2mIssue(client.apiKey(), issueBody(schemaId, validHolderRef()));

    assertThat(issued.getStatusCode()).as(issued.getBody()).isEqualTo(HttpStatus.OK);
    JsonNode body = tree(issued.getBody());
    assertThat(body.get("issuerClientId").asText()).isEqualTo(client.id());
    assertThat(body.get("claimed").asBoolean()).isFalse();
    String ref = body.get("ref").asText();

    TenantContext.set(TenantContext.DEFAULT_TENANT_ID, TenantContext.DEFAULT_TENANT_SLUG);
    try {
      assertThat(
              jdbc.queryForObject(
                  "SELECT issuer_client_id::text FROM credential WHERE ref = ?", String.class, ref))
          .isEqualTo(client.id());
      Map<String, Object> audit =
          jdbc.queryForMap(
              "SELECT actor_type, actor_id::text AS actor_id FROM audit_log"
                  + " WHERE action = 'CREDENTIAL_ISSUED' AND entity_ref = ?",
              ref);
      assertThat(audit.get("actor_type")).isEqualTo("API_KEY");
      assertThat(audit.get("actor_id")).isEqualTo(client.id());
      // Console issuance leaves the column NULL.
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void issue_againstASchemaOutsideTheAllowlist_isDenied403_andNothingIsIssued() {
    AuthenticatedSession admin = adminSession();
    String allowed = publishedSchemaInDefaultTenant("m2m-allowed");
    String other = publishedSchemaInDefaultTenant("m2m-other");
    ClientKey client = createClient(admin, List.of(allowed));

    ResponseEntity<String> denied = m2mIssue(client.apiKey(), issueBody(other, validHolderRef()));

    assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(tree(denied.getBody()).get("code").asText()).isEqualTo("KH-AUTH-0403");
    ClientKey noSchemas = createClient(admin, List.of());
    assertThat(m2mIssue(noSchemas.apiKey(), issueBody(allowed, validHolderRef())).getStatusCode())
        .as("deny-by-default: a client with an empty allowlist can issue nothing")
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void bulk_isAllowedForIssuerClients_andCanMintTheClaimCode() {
    AuthenticatedSession admin = adminSession();
    PublishedSchema schema = publishedSchemaWithCode("m2m-bulk");
    ClientKey client = createClient(admin, List.of(schema.id()));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaCode", schema.code());
    body.put("mintClaimCodes", true);
    body.put(
        "items", List.of(Map.of("claims", Map.of("field", "v"), "pseudoRef", validHolderRef())));
    ResponseEntity<String> response =
        m2m(HttpMethod.POST, "/api/v1/credentials/bulk", client.apiKey(), body);

    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    JsonNode item = tree(response.getBody()).get("results").get(0);
    assertThat(item.get("status").asText()).isEqualTo("ISSUED");
    assertThat(item.get("claimCode").asText()).isNotBlank();
  }

  // ── D4/V6 — reading: own credentials only ───────────────────────────────────────────────────

  @Test
  void getCredential_ownIs200_aSiblingsOrConsoleIssuedIs404() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("m2m-read");
    ClientKey a = createClient(admin, List.of(schemaId));
    ClientKey b = createClient(admin, List.of(schemaId));

    String aCredential =
        tree(m2mIssue(a.apiKey(), issueBody(schemaId, validHolderRef())).getBody())
            .get("id")
            .asText();
    ResponseEntity<String> consoleIssued =
        SessionTestSupport.post(rest, ISSUE, admin, issueBody(schemaId, validHolderRef()));
    assertThat(consoleIssued.getStatusCode()).isEqualTo(HttpStatus.OK);
    String consoleCredential = tree(consoleIssued.getBody()).get("id").asText();

    assertThat(
            m2m(HttpMethod.GET, "/api/v1/credentials/" + aCredential, a.apiKey(), null)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    ResponseEntity<String> sibling =
        m2m(HttpMethod.GET, "/api/v1/credentials/" + aCredential, b.apiKey(), null);
    assertThat(sibling.getStatusCode())
        .as("not 403 — existence must not be probeable")
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(tree(sibling.getBody()).get("code").asText()).isEqualTo("KH-CRD-0404");
    assertThat(
            m2m(HttpMethod.GET, "/api/v1/credentials/" + consoleCredential, a.apiKey(), null)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            m2m(HttpMethod.GET, "/api/v1/credentials/" + UUID.randomUUID(), a.apiKey(), null)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ── D4 — the central scope rule, against every registered route ─────────────────────────────

  /** Routes an issuer client may reach (D4) plus the deliberately public ones (no credential). */
  private static final Set<String> NOT_DENIED =
      Set.of(
          "POST /api/v1/credentials/issue",
          "POST /api/v1/credentials/bulk",
          "GET /api/v1/credentials/{id}",
          "POST /api/v1/credentials/verify",
          "POST /api/v1/credentials/holder-status",
          "POST /api/v1/claims/redeem",
          "POST /api/v1/auth/login",
          "POST /api/v1/auth/totp");

  @Test
  void everyOtherRegisteredRoute_isDeniedWithKhAuth0403_forAValidKey() {
    AuthenticatedSession admin = adminSession();
    ClientKey client = createClient(admin, List.of());

    List<String> checked = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      Set<String> patterns =
          info.getPathPatternsCondition() == null
              ? Set.of()
              : info.getPathPatternsCondition().getPatternValues();
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      List<RequestMethod> effective =
          methods.isEmpty() ? List.of(RequestMethod.GET) : List.copyOf(methods);
      for (String pattern : patterns) {
        if (!pattern.startsWith("/api/v1/")) {
          continue;
        }
        for (RequestMethod method : effective) {
          String label = method + " " + pattern;
          if (NOT_DENIED.contains(label)) {
            continue;
          }
          String path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
          boolean hasBody =
              method == RequestMethod.POST
                  || method == RequestMethod.PUT
                  || method == RequestMethod.PATCH;
          ResponseEntity<String> response =
              m2m(
                  HttpMethod.valueOf(method.name()),
                  path,
                  client.apiKey(),
                  hasBody ? Map.of() : null);
          checked.add(label);
          if (response.getStatusCode() != HttpStatus.FORBIDDEN
              || response.getBody() == null
              || !response.getBody().contains("\"KH-AUTH-0403\"")) {
            failures.add(label + " -> " + response.getStatusCode());
          }
        }
      }
    }

    assertThat(checked).as("routes exercised").hasSizeGreaterThan(30);
    assertThat(failures)
        .as("every route not on the issuer-client allowlist must be KH-AUTH-0403")
        .isEmpty();
  }

  // ── NFR-07 — tenant isolation ───────────────────────────────────────────────────────────────

  @Test
  void aClientCannotSeeOrUseAnotherTenantsSchema() {
    AuthenticatedSession admin = adminSession();
    Org other = onboardTenant(admin, "m2m-iso");
    String foreignSchema = publishedSchemaIn(other.id(), other.slug(), "m2m-foreign");
    String ownSchema = publishedSchemaInDefaultTenant("m2m-own");
    ClientKey client = createClient(admin, List.of(ownSchema));

    ResponseEntity<String> issued =
        m2mIssue(client.apiKey(), issueBody(foreignSchema, validHolderRef()));
    assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<String> allowlisted =
        SessionTestSupport.post(
            rest,
            CLIENTS,
            admin,
            Map.of("name", nameBody(), "allowedSchemaIds", List.of(foreignSchema)));
    assertThat(allowlisted.getStatusCode())
        .as("a foreign schema id is invisible under RLS, so it cannot even be allowlisted")
        .isEqualTo(HttpStatus.BAD_REQUEST);

    TenantContext.set(other.id(), other.slug());
    try {
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential", Integer.class)).isZero();
    } finally {
      TenantContext.clear();
    }
  }

  // ── D12 — indistinguishable failures, distinguishable audit ─────────────────────────────────

  @Test
  void everyFailureFlavor_returnsTheSameBody_butAuditsItsOwnReason() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("m2m-fail");

    ClientKey suspended = createClient(admin, List.of(schemaId));
    SessionTestSupport.post(rest, CLIENTS + "/" + suspended.id() + "/suspend", admin, Map.of());
    ClientKey revoked = createClient(admin, List.of(schemaId));
    SessionTestSupport.post(rest, CLIENTS + "/" + revoked.id() + "/revoke", admin, Map.of());
    ClientKey retired = createClient(admin, List.of(schemaId));
    SessionTestSupport.post(
        rest, CLIENTS + "/" + retired.id() + "/rotate", admin, Map.of("retireAfterHours", 0));
    ClientKey wrongSecret = createClient(admin, List.of(schemaId));
    ResponseEntity<String> expiringCreate =
        SessionTestSupport.post(
            rest,
            CLIENTS,
            admin,
            Map.of(
                "name",
                nameBody(),
                "allowedSchemaIds",
                List.of(schemaId),
                "expiresAt",
                clock.instant().plus(Duration.ofHours(1)).toString()));
    ClientKey expiring = readCreated(expiringCreate.getBody());
    assertThat(m2mIssue(expiring.apiKey(), issueBody(schemaId, validHolderRef())).getStatusCode())
        .as("still valid before expires_at")
        .isEqualTo(HttpStatus.OK);
    clock.advance(Duration.ofHours(2));

    String unknownKey = "khi_" + randomPrefix() + "_" + "A".repeat(43);
    String tamperedKey =
        wrongSecret.apiKey().substring(0, wrongSecret.apiKey().length() - 1)
            + (wrongSecret.apiKey().endsWith("A") ? "B" : "A");
    Map<String, String> keys = new LinkedHashMap<>();
    keys.put("unknown-prefix", unknownKey);
    keys.put("malformed", "khi_not-a-key");
    keys.put("bad-secret", tamperedKey);
    keys.put("suspended", suspended.apiKey());
    keys.put("revoked", revoked.apiKey());
    keys.put("retired", retired.apiKey());
    keys.put("expired", expiring.apiKey());

    JsonNode reference = null;
    for (Map.Entry<String, String> entry : keys.entrySet()) {
      ResponseEntity<String> response =
          m2mIssue(entry.getValue(), issueBody(schemaId, validHolderRef()));
      assertThat(response.getStatusCode()).as(entry.getKey()).isEqualTo(HttpStatus.UNAUTHORIZED);
      JsonNode envelope = stableEnvelope(response.getBody());
      assertThat(envelope.get("code").asText()).as(entry.getKey()).isEqualTo("KH-AUTH-0401");
      if (reference == null) {
        reference = envelope;
      }
      assertThat(envelope)
          .as("body for " + entry.getKey() + " equals the unknown-key body")
          .isEqualTo(reference);
    }

    assertReason(suspended.prefix(), "suspended");
    assertReason(revoked.prefix(), "revoked");
    assertReason(retired.prefix(), "retired");
    assertReason(expiring.prefix(), "expired");
    assertReason(wrongSecret.prefix(), "bad-secret");
    assertReason(prefixOf(unknownKey), "unknown-prefix");
    TenantContext.set(TenantContext.DEFAULT_TENANT_ID, TenantContext.DEFAULT_TENANT_SLUG);
    try {
      // The suspended/revoked rows carry the registry-only KH-ICL-0409 internally.
      assertThat(
              jdbc.queryForObject(
                  "SELECT detail::jsonb ->> 'code' FROM audit_log"
                      + " WHERE action = 'ISSUER_CLIENT_AUTH_FAILED' AND entity_ref = ?",
                  String.class,
                  suspended.prefix()))
          .isEqualTo("KH-ICL-0409");
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void repeatedFailuresForOnePrefix_areAuditedOncePerWindow() {
    String prefix = randomPrefix();
    String key = "khi_" + prefix + "_" + "B".repeat(43);
    for (int i = 0; i < 50; i++) {
      assertThat(m2mIssue(key, Map.of()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    assertThat(auditCountDefaultTenant("ISSUER_CLIENT_AUTH_FAILED", prefix))
        .as("50 failures inside one window collapse into a single audit row")
        .isEqualTo(1);

    clock.advance(Duration.ofSeconds(61));
    m2mIssue(key, Map.of());
    assertThat(auditCountDefaultTenant("ISSUER_CLIENT_AUTH_FAILED", prefix))
        .as("the next window records again")
        .isEqualTo(2);
  }

  @Test
  void noAutomaticLockout_aWrongKeyNeverBlocksTheRealOne() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("m2m-lockout");
    ClientKey client = createClient(admin, List.of(schemaId));
    String wrong =
        client.apiKey().substring(0, client.apiKey().length() - 1)
            + (client.apiKey().endsWith("A") ? "B" : "A");
    for (int i = 0; i < 12; i++) {
      assertThat(m2mIssue(wrong, Map.of()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    assertThat(m2mIssue(client.apiKey(), issueBody(schemaId, validHolderRef())).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  // ── D8 — the connector contract, every issuance path ────────────────────────────────────────

  @Test
  void holderRefShape_andForbiddenClaimNames_areEnforcedForConsoleAndMachineAlike() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("m2m-contract");
    ClientKey client = createClient(admin, List.of(schemaId));

    List<String> badRefs =
        List.of("holder-1", "A".repeat(64), "a".repeat(63), "a".repeat(65), "g".repeat(64));
    for (String bad : badRefs) {
      assertContract(
          SessionTestSupport.post(rest, ISSUE, admin, issueBody(schemaId, bad)),
          "console holderRef " + bad.length());
      assertContract(
          m2mIssue(client.apiKey(), issueBody(schemaId, bad)), "m2m holderRef " + bad.length());
    }

    for (String forbidden :
        List.of("nationalId", "NationalId", "NID", "national_id", "SSN", "passportNo")) {
      Map<String, Object> body = issueBody(schemaId, validHolderRef());
      body.put("claims", Map.of(forbidden, "x"));
      assertContract(
          SessionTestSupport.post(rest, ISSUE, admin, body), "console claim " + forbidden);
      assertContract(m2mIssue(client.apiKey(), body), "m2m claim " + forbidden);
    }
    assertThat(m2mIssue(client.apiKey(), issueBody(schemaId, validHolderRef())).getStatusCode())
        .as("a conforming request still succeeds")
        .isEqualTo(HttpStatus.OK);
  }

  private static void assertContract(ResponseEntity<String> response, String label) {
    assertThat(response.getStatusCode()).as(label).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(tree(response.getBody()).get("code").asText()).as(label).isEqualTo("KH-ISS-0400");
  }

  // ── §3.5 / SEC §9 — nothing secret in any log line ──────────────────────────────────────────

  @Test
  void createFailedAuthAndIssue_neverLogTheKey_theSecret_orTheHolderRef() {
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    Logger platform = (Logger) LoggerFactory.getLogger("sy.khatm");
    Level previous = platform.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    platform.setLevel(Level.DEBUG);
    try {
      AuthenticatedSession admin = adminSession();
      String schemaId = publishedSchemaInDefaultTenant("m2m-logs");
      ClientKey client = createClient(admin, List.of(schemaId));
      String holderRef = validHolderRef();
      String tampered =
          client.apiKey().substring(0, client.apiKey().length() - 1)
              + (client.apiKey().endsWith("A") ? "B" : "A");
      m2mIssue(tampered, issueBody(schemaId, holderRef));
      assertThat(m2mIssue(client.apiKey(), issueBody(schemaId, holderRef)).getStatusCode())
          .isEqualTo(HttpStatus.OK);
      m2mIssue(client.apiKey(), issueBody(schemaId, "not-hex")); // a rejected request too

      String secret = client.apiKey().substring("khi_".length() + 10 + 1);
      List<String> lines = new ArrayList<>();
      for (ILoggingEvent event : List.copyOf(appender.list)) {
        lines.add(event.getFormattedMessage());
        if (event.getThrowableProxy() != null) {
          lines.add(event.getThrowableProxy().getMessage());
        }
      }
      assertThat(lines).isNotEmpty();
      assertThat(lines).noneMatch(line -> line != null && line.contains(client.apiKey()));
      assertThat(lines).noneMatch(line -> line != null && line.contains(secret));
      assertThat(lines).noneMatch(line -> line != null && line.contains(tampered));
      assertThat(lines).noneMatch(line -> line != null && line.contains(holderRef));
    } finally {
      root.detachAppender(appender);
      platform.setLevel(previous);
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────

  private void assertReason(String prefix, String reason) {
    TenantContext.set(TenantContext.DEFAULT_TENANT_ID, TenantContext.DEFAULT_TENANT_SLUG);
    try {
      List<String> reasons =
          jdbc.queryForList(
              "SELECT detail::jsonb ->> 'reason' FROM audit_log"
                  + " WHERE action = 'ISSUER_CLIENT_AUTH_FAILED' AND entity_ref = ?",
              String.class,
              prefix);
      assertThat(reasons).as("audit reason for " + prefix).containsExactly(reason);
    } finally {
      TenantContext.clear();
    }
  }

  private static String prefixOf(String rawKey) {
    return rawKey.substring("khi_".length(), "khi_".length() + 10);
  }

  private static String randomPrefix() {
    SecureRandom random = new SecureRandom();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      sb.append(BASE32.charAt(random.nextInt(BASE32.length())));
    }
    return sb.toString();
  }
}
