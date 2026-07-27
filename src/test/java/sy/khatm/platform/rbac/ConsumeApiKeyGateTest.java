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
import sy.khatm.platform.consumer.api.ConsumingPartyAdmin;
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
 * Spec FS-0.6b DoD #4 — {@code /consume} works with a valid {@code CONSUMING_PARTY} API key and
 * records {@code CREDENTIAL_CONSUMED} via {@code AuditService}; a revoked/malformed key gets {@code
 * KH-RBC-1401} + {@code API_KEY_AUTH_FAILED}; a console session gets {@code 403} — consuming is
 * API-key-only (SEC §7).
 *
 * <p><b>KH-1.4.3</b> extends this gate: a {@code CONSUMING_PARTY} key may only consume a credential
 * whose schema is in its own {@code consuming_party_schema} allowlist (deny-by-default) — the
 * happy-path test below now allowlists its party explicitly, rather than relying on the
 * pre-KH-1.4.3 find-or-create-by-caller-supplied-string shortcut that {@code
 * CredentialService#consume} no longer trusts for authorization. Also proves the {@code
 * TENANT}-key-with-{@code consume}-scope case SEC §7 names explicitly: {@code SecurityConfig}'s
 * existing {@code ScopeGuard.requireScopeAndConsumingPartyKey} rule already rejects it, closing
 * that FS-0.6b ambiguity — no new production code was needed for that half, only this explicit
 * test.
 */
class ConsumeApiKeyGateTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConsumingPartyRegistry consumingParties;
  @Autowired private ConsumingPartyAdmin consumingPartyAdmin;
  @Autowired private SchemaCatalog schemaCatalog;

  @Test
  void consume_withValidConsumingPartyKey_works_andRecordsAuditRow() throws Exception {
    String schemaCode = "ConsumeGateProbe/v1";
    UUID schemaId = ensureSchema(schemaCode);
    String issuerRawKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue")).rawKey();
    String credentialId = issueCredential(issuerRawKey, schemaCode);

    ConsumingPartyRef party = consumingParties.ensure("gate-test-consumer-" + UUID.randomUUID());
    consumingParties.allowSchema(party.id(), schemaId);
    CreatedApiKey consumerKey =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, party.id(), Set.of("consume"));

    ResponseEntity<String> response =
        consume(consumerKey.rawKey(), credentialId, "gate-test-consumer");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("consumed").asBoolean()).isTrue();

    Integer auditCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CREDENTIAL_CONSUMED'"
                + " AND entity_ref = ?",
            Integer.class,
            credentialId);
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void consume_withSchemaNotInPartyAllowlist_returns403WithNewCode_andRecordsAudit()
      throws Exception {
    String allowedSchemaCode = "ConsumeGateAllowedProbe/v1";
    String otherSchemaCode = "ConsumeGateOtherProbe/v1";
    UUID allowedSchemaId = ensureSchema(allowedSchemaCode);
    ensureSchema(otherSchemaCode);
    String issuerRawKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue")).rawKey();
    // The credential being consumed is issued against otherSchemaCode, not the schema the party
    // is allowlisted for.
    String credentialId = issueCredential(issuerRawKey, otherSchemaCode);

    ConsumingPartyRef party = consumingParties.ensure("gate-test-not-allowed-" + UUID.randomUUID());
    consumingParties.allowSchema(party.id(), allowedSchemaId);
    CreatedApiKey consumerKey =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, party.id(), Set.of("consume"));

    ResponseEntity<String> response =
        consume(consumerKey.rawKey(), credentialId, "gate-test-not-allowed");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-CNS-0403");

    Integer auditCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CONSUME_SCHEMA_DENIED'"
                + " AND entity_ref = (SELECT ref FROM credential WHERE id = ?::uuid)",
            Integer.class,
            credentialId);
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void consume_withEmptyAllowlist_returns403() throws Exception {
    String schemaCode = "ConsumeGateEmptyAllowlistProbe/v1";
    ensureSchema(schemaCode);
    String issuerRawKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue")).rawKey();
    String credentialId = issueCredential(issuerRawKey, schemaCode);

    // A party with zero consuming_party_schema rows at all — never allowlisted for anything.
    ConsumingPartyRef party = consumingParties.ensure("gate-test-empty-" + UUID.randomUUID());
    CreatedApiKey consumerKey =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, party.id(), Set.of("consume"));

    ResponseEntity<String> response =
        consume(consumerKey.rawKey(), credentialId, "gate-test-empty");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-CNS-0403");
  }

  @Test
  void consume_withUnresolvableCredentialId_returns403_notAllowed() throws Exception {
    // KH-2.1 Part B regression pin: CredentialService#enforceSchemaAllowlist's fallback for "can't
    // resolve this credential's schema" must be deny, not the pre-fix silent allow that a missing
    // RLS tenant context on the bare findSchemaId call used to produce for every well-formed but
    // unresolvable id (this test predates that bug existing, but a well-formed random UUID with no
    // matching credential row exercises the exact same code path deterministically).
    ConsumingPartyRef party =
        consumingParties.ensure("gate-test-unresolvable-" + UUID.randomUUID());
    CreatedApiKey consumerKey =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, party.id(), Set.of("consume"));

    ResponseEntity<String> response =
        consume(consumerKey.rawKey(), UUID.randomUUID().toString(), "gate-test-unresolvable");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-CNS-0403");
  }

  @Test
  void consume_withTenantKeyHavingConsumeScope_returns403() throws Exception {
    // SEC §7: consumption belongs to CONSUMING_PARTY keys only — a TENANT key is rejected even
    // when explicitly granted the consume scope. Enforced today by SecurityConfig's
    // ScopeGuard.requireScopeAndConsumingPartyKey rule (no new code this session); this test
    // closes the FS-0.6b ambiguity the KH-1.4.3 brief called out with an explicit assertion.
    CreatedApiKey tenantKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("consume"));

    ResponseEntity<String> response =
        consume(tenantKey.rawKey(), UUID.randomUUID().toString(), "gate-test-tenant-key");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void consume_withSuspendedParty_returns401_andWorksAgainAfterActivate() throws Exception {
    // KH-1.4.4 D4: a SUSPENDED party's key must fail authentication exactly like a revoked key
    // (401 KH-RBC-1401 + API_KEY_AUTH_FAILED), and authenticate again once reactivated.
    String schemaCode = "ConsumeSuspendProbe/v1";
    UUID schemaId = ensureSchema(schemaCode);
    String issuerRawKey =
        apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue")).rawKey();
    String firstCredential = issueCredential(issuerRawKey, schemaCode);

    ConsumingPartyRef party = consumingParties.ensure("suspend-test-" + UUID.randomUUID());
    consumingParties.allowSchema(party.id(), schemaId);
    CreatedApiKey consumerKey =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, party.id(), Set.of("consume"));

    // Active: consume succeeds.
    assertThat(consume(consumerKey.rawKey(), firstCredential, "suspend-test").getStatusCode())
        .isEqualTo(HttpStatus.OK);

    // Suspended: the same key now fails authentication like a revoked one.
    consumingPartyAdmin.suspend(party.id());
    ResponseEntity<String> whileSuspended =
        consume(consumerKey.rawKey(), firstCredential, "suspend-test");
    assertThat(whileSuspended.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(JSON.readTree(whileSuspended.getBody()).get("code").asText())
        .isEqualTo("KH-RBC-1401");
    Integer authFailed =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'API_KEY_AUTH_FAILED'"
                + " AND entity_ref = ?",
            Integer.class,
            consumerKey.keyPrefix());
    assertThat(authFailed).isGreaterThanOrEqualTo(1);

    // Reactivated: the key authenticates again and consumes a fresh credential cleanly.
    String secondCredential = issueCredential(issuerRawKey, schemaCode);
    consumingPartyAdmin.activate(party.id());
    ResponseEntity<String> afterActivate =
        consume(consumerKey.rawKey(), secondCredential, "suspend-test");
    assertThat(afterActivate.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(afterActivate.getBody()).get("consumed").asBoolean()).isTrue();
  }

  @Test
  void consume_withRevokedKey_returns401_andRecordsApiKeyAuthFailed() throws Exception {
    CreatedApiKey key =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, null, Set.of("consume"));
    apiKeyService.revoke(key.id());

    ResponseEntity<String> response =
        consume(key.rawKey(), UUID.randomUUID().toString(), "gate-test-revoked");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-1401");

    Integer auditCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'API_KEY_AUTH_FAILED'"
                + " AND entity_ref = ?",
            Integer.class,
            key.keyPrefix());
    assertThat(auditCount).isGreaterThanOrEqualTo(1);
  }

  @Test
  void consume_withMalformedKey_returns401() throws Exception {
    ResponseEntity<String> response =
        consume("khk_live_not-a-real-key.garbage", UUID.randomUUID().toString(), "gate-test-bad");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-1401");
  }

  @Test
  void consume_withConsoleSession_returns403() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            "/api/v1/credentials/consume",
            session,
            Map.of("id", UUID.randomUUID().toString(), "consumer", "gate-test-session"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  private UUID ensureSchema(String code) {
    SchemaRef ref =
        schemaCatalog.ensurePublished(
            new SchemaDefinition(code, 1, new LocalizedText(code, code), "{}", List.of(), 1));
    return ref.id();
  }

  private String issueCredential(String rawApiKey, String schemaCode) throws Exception {
    Map<String, Object> issueRequest =
        Map.of(
            "schemaCode",
            schemaCode,
            "holderRef",
            "holder-consume-gate-" + UUID.randomUUID(),
            "claims",
            Map.of("field", "value"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawApiKey);
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/credentials/issue",
            HttpMethod.POST,
            new HttpEntity<>(issueRequest, headers),
            String.class);
    JsonNode body = JSON.readTree(response.getBody());
    return body.get("id").asText();
  }

  private ResponseEntity<String> consume(String rawApiKey, String credentialId, String consumer) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawApiKey);
    Map<String, Object> body = Map.of("id", credentialId, "consumer", consumer);
    return rest.exchange(
        "/api/v1/credentials/consume",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }
}
