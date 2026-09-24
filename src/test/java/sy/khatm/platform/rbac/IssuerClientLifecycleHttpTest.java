package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.issuerclient.domain.IssuerClientLifecycle;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.shared.TenantContext;

/**
 * KH-2.8.1 (spec FS-2.7a D1/D2/D10/D11) — issuer-client administration over real HTTP: the key is
 * shown once and never stored, the lifecycle transitions and their audit rows, and rotation with a
 * grace window (moved with an injected {@link java.time.Clock}, never with {@code sleep}).
 */
class IssuerClientLifecycleHttpTest extends IssuerClientHttpTestSupport {

  @Autowired private IssuerClientLifecycle lifecycle;

  @Test
  void create_showsKeyOnce_listNeverDoes_andNoTableStoresIt() {
    AuthenticatedSession admin = adminSession();
    ClientKey created = createClient(admin, List.of());

    assertThat(created.apiKey()).startsWith("khi_");
    assertThat(created.prefix()).hasSize(10).doesNotContain("khi_");
    assertThat(created.apiKey()).startsWith("khi_" + created.prefix() + "_");

    ResponseEntity<String> list = SessionTestSupport.get(rest, CLIENTS, admin);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getBody()).contains(created.prefix());
    assertThat(list.getBody()).doesNotContain(created.apiKey()).doesNotContain("khi_");
    String secret = created.apiKey().substring("khi_".length() + 10 + 1);
    assertThat(list.getBody()).doesNotContain(secret).doesNotContain("apiKeyHash");

    // Dump every text-ish column of the two tables (and the audit trail): the raw key, its secret,
    // and even the literal "khi_" tag must appear nowhere. The stored prefix is deliberately
    // tag-less so this check is decisive.
    TenantContext.set(TenantContext.DEFAULT_TENANT_ID, TenantContext.DEFAULT_TENANT_SLUG);
    try {
      List<String> dump =
          jdbc.queryForList(
              "SELECT row_to_json(t)::text FROM issuer_client t"
                  + " UNION ALL SELECT row_to_json(s)::text FROM issuer_client_schema s"
                  + " UNION ALL SELECT row_to_json(a)::text FROM audit_log a",
              String.class);
      assertThat(dump).isNotEmpty();
      assertThat(dump).noneMatch(row -> row.contains("khi_"));
      assertThat(dump).noneMatch(row -> row.contains(secret));
      // What IS stored: the prefix and an argon2id hash (as bytea), nothing reversible.
      String hashText =
          jdbc.queryForObject(
              "SELECT convert_from(api_key_hash, 'UTF8') FROM issuer_client WHERE key_prefix = ?",
              String.class,
              created.prefix());
      assertThat(hashText).startsWith("$argon2id$");
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void create_withUnknownSchemaId_isRejected400() {
    AuthenticatedSession admin = adminSession();
    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            CLIENTS,
            admin,
            Map.of("name", nameBody(), "allowedSchemaIds", List.of(UUID.randomUUID().toString())));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(tree(response.getBody()).get("code").asText()).isEqualTo("KH-ICL-0400");
  }

  @Test
  void adminPlane_requiresAConsoleSession() {
    ResponseEntity<String> anonymous = rest.getForEntity(CLIENTS, String.class);
    assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void suspend_resume_revoke_transitionsAndAudit() {
    AuthenticatedSession admin = adminSession();
    ClientKey client = createClient(admin, List.of());
    String base = CLIENTS + "/" + client.id();

    assertThat(status(SessionTestSupport.post(rest, base + "/resume", admin, Map.of())))
        .as("resume on an ACTIVE client is an invalid transition")
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(SessionTestSupport.post(rest, base + "/suspend", admin, Map.of()).getBody())
        .contains("\"status\":\"SUSPENDED\"");
    assertThat(status(SessionTestSupport.post(rest, base + "/suspend", admin, Map.of())))
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(SessionTestSupport.post(rest, base + "/resume", admin, Map.of()).getBody())
        .contains("\"status\":\"ACTIVE\"");
    assertThat(SessionTestSupport.post(rest, base + "/revoke", admin, Map.of()).getBody())
        .contains("\"status\":\"REVOKED\"");
    assertThat(status(SessionTestSupport.post(rest, base + "/revoke", admin, Map.of())))
        .isEqualTo(HttpStatus.CONFLICT);
    ResponseEntity<String> unknown =
        SessionTestSupport.post(
            rest, CLIENTS + "/" + UUID.randomUUID() + "/suspend", admin, Map.of());
    assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(tree(unknown.getBody()).get("code").asText()).isEqualTo("KH-ICL-0404");

    for (String action :
        List.of(
            "ISSUER_CLIENT_CREATED",
            "ISSUER_CLIENT_SUSPENDED",
            "ISSUER_CLIENT_RESUMED",
            "ISSUER_CLIENT_REVOKED")) {
      assertThat(auditCountDefaultTenant(action, client.prefix()))
          .as("audit row %s (entity_ref = key prefix)", action)
          .isEqualTo(1);
    }
  }

  @Test
  void rotate_gracePeriod_oldWorksUntilRetireAfter_newWorksImmediately_sweepFinishesIt() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("rot");
    ClientKey old = createClient(admin, List.of(schemaId));

    ResponseEntity<String> rotated =
        SessionTestSupport.post(
            rest, CLIENTS + "/" + old.id() + "/rotate", admin, Map.of("retireAfterHours", 1));
    assertThat(rotated.getStatusCode()).as(rotated.getBody()).isEqualTo(HttpStatus.CREATED);
    ClientKey replacement = readCreated(rotated.getBody());
    JsonNode json = tree(rotated.getBody());
    assertThat(json.get("retiringClientId").asText()).isEqualTo(old.id());
    assertThat(json.get("client").get("rotatedFrom").asText()).isEqualTo(old.id());
    assertThat(json.get("client").get("allowedSchemaIds").get(0).asText()).isEqualTo(schemaId);
    assertThat(replacement.holderSecret()).as("a rotation never returns a holder secret").isNull();

    // Inside the window: BOTH keys issue.
    assertThat(m2mIssue(old.apiKey(), issueBody(schemaId, validHolderRef())).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            m2mIssue(replacement.apiKey(), issueBody(schemaId, validHolderRef())).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(statusOfClient(admin, old.id())).isEqualTo("RETIRING");

    // Past retire_after: the old key is refused even before the sweep has run.
    clock.advance(Duration.ofMinutes(61));
    ResponseEntity<String> refused = m2mIssue(old.apiKey(), issueBody(schemaId, validHolderRef()));
    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(tree(refused.getBody()).get("code").asText()).isEqualTo("KH-AUTH-0401");
    assertThat(
            m2mIssue(replacement.apiKey(), issueBody(schemaId, validHolderRef())).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    // The sweep's per-client step converts the stored status and audits it.
    TenantContext.set(TenantContext.DEFAULT_TENANT_ID, TenantContext.DEFAULT_TENANT_SLUG);
    try {
      assertThat(lifecycle.finishRetirement(UUID.fromString(old.id()))).isTrue();
      assertThat(lifecycle.finishRetirement(UUID.fromString(old.id()))).isFalse();
    } finally {
      TenantContext.clear();
    }
    assertThat(statusOfClient(admin, old.id())).isEqualTo("REVOKED");
    assertThat(auditCountDefaultTenant("ISSUER_CLIENT_ROTATED", old.prefix())).isEqualTo(1);
    assertThat(auditCountDefaultTenant("ISSUER_CLIENT_REVOKED", old.prefix())).isEqualTo(1);

    // A revoked client cannot be rotated; only ACTIVE ones can.
    ResponseEntity<String> again =
        SessionTestSupport.post(rest, CLIENTS + "/" + old.id() + "/rotate", admin, Map.of());
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(tree(again.getBody()).get("code").asText()).isEqualTo("KH-ICL-1409");
  }

  @Test
  void rotate_retireWindowIsBoundedTo0Through72Hours() {
    AuthenticatedSession admin = adminSession();
    ClientKey client = createClient(admin, List.of());
    ResponseEntity<String> tooLong =
        SessionTestSupport.post(
            rest, CLIENTS + "/" + client.id() + "/rotate", admin, Map.of("retireAfterHours", 73));
    assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(tree(tooLong.getBody()).get("code").asText()).isEqualTo("KH-ICL-0400");
    ResponseEntity<String> negative =
        SessionTestSupport.post(
            rest, CLIENTS + "/" + client.id() + "/rotate", admin, Map.of("retireAfterHours", -1));
    assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    // 72 exactly is the ceiling and is accepted; omitting the body uses the 24h default.
    ResponseEntity<String> atCeiling =
        SessionTestSupport.post(
            rest, CLIENTS + "/" + client.id() + "/rotate", admin, Map.of("retireAfterHours", 72));
    assertThat(atCeiling.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ClientKey next = readCreated(atCeiling.getBody());
    ResponseEntity<String> defaulted =
        SessionTestSupport.post(rest, CLIENTS + "/" + next.id() + "/rotate", admin, null);
    assertThat(defaulted.getStatusCode()).as(defaulted.getBody()).isEqualTo(HttpStatus.CREATED);
    Duration window =
        Duration.between(
            java.time.Instant.parse(
                tree(SessionTestSupport.get(rest, CLIENTS, admin).getBody())
                    .findParents("id")
                    .stream()
                    .filter(n -> n.get("id").asText().equals(next.id()))
                    .findFirst()
                    .orElseThrow()
                    .get("retireAfter")
                    .asText()),
            clock.instant());
    assertThat(window.abs()).isBetween(Duration.ofHours(23), Duration.ofHours(25));
  }

  private String statusOfClient(AuthenticatedSession admin, String id) {
    for (JsonNode node : tree(SessionTestSupport.get(rest, CLIENTS, admin).getBody())) {
      if (node.get("id").asText().equals(id)) {
        return node.get("status").asText();
      }
    }
    throw new AssertionError("client not listed: " + id);
  }

  private static HttpStatus status(ResponseEntity<String> response) {
    return HttpStatus.valueOf(response.getStatusCode().value());
  }
}
