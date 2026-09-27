package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.credential.domain.IssuanceIdempotency;
import sy.khatm.platform.credential.domain.IssuanceIdempotencyGuard;
import sy.khatm.platform.credential.persistence.ClaimCodeRepository;
import sy.khatm.platform.credential.persistence.IssuanceIdempotencyRepository;
import sy.khatm.platform.credential.worker.ClaimCodeExpiryWorker;
import sy.khatm.platform.credential.worker.IdempotencyRetentionSweeper;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * KH-2.8.2-BE (spec FS-2.7a D5–D7, brief {@code docs/sessions/SESSION-KH-2.8.2-BE.md} §5): issuance
 * idempotency, claim-code delivery over M2M ({@code mintClaimCode}), and the optional {@code
 * holderRef} for human sessions (veto V1-b) — all over real HTTP, against the real filter chain.
 */
class IssuanceIdempotencyHttpTest extends IssuerClientHttpTestSupport {

  private static final String ISSUE = "/api/v1/credentials/issue";
  private static final String BULK = "/api/v1/credentials/bulk";
  private static final String REDEEM = "/api/v1/claims/redeem";
  private static final String REPLAYED = "Idempotent-Replayed";

  @Autowired private IssuanceIdempotencyGuard guard;
  @Autowired private IssuanceIdempotencyRepository idempotencyRows;
  @Autowired private ClaimCodeRepository claimCodes;
  @Autowired private AuditService audit;
  @Autowired private SystemAccessExecutor systemAccess;
  @Autowired private StringRedisTemplate redis;

  /** Every redeem counts against a per-IP window shared by the whole JVM; start each test clean. */
  @BeforeEach
  void clearRedeemThrottle() {
    Set<String> keys = redis.keys("khatm:claims:redeem:throttle:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }

  // ── #2 — who must send the header ───────────────────────────────────────────────────────────

  @Test
  void m2mWithoutKey_isKhIdem0400_onIssueAndBulk_whileAHumanSessionNeedsNone() {
    AuthenticatedSession admin = adminSession();
    PublishedSchema schema = publishedSchemaWithCode("idem-header");
    ClientKey client = createClient(admin, List.of(schema.id()));

    assertIdem0400(m2m(HttpMethod.POST, ISSUE, client.apiKey(), issueBody(schema.id(), ref())));
    assertIdem0400(m2m(HttpMethod.POST, BULK, client.apiKey(), bulkBody(schema.code(), false)));
    assertIdem0400(m2mIssue(client.apiKey(), issueBody(schema.id(), ref()), "k".repeat(129)));
    assertIdem0400(m2mIssue(client.apiKey(), issueBody(schema.id(), ref()), "   "));
    assertThat(
            count("SELECT COUNT(*) FROM credential WHERE issuer_client_id = ?::uuid", client.id()))
        .as("a rejected key issues nothing")
        .isZero();

    ResponseEntity<String> human =
        SessionTestSupport.post(rest, ISSUE, admin, issueBody(schema.id(), ref()));
    assertThat(human.getStatusCode()).as(human.getBody()).isEqualTo(HttpStatus.OK);
    assertThat(human.getHeaders().containsKey(REPLAYED)).isFalse();

    // V6: a human session that does send a key has it honored.
    String key = "console-" + UUID.randomUUID();
    Map<String, Object> body = issueBody(schema.id(), ref());
    ResponseEntity<String> first = sessionIssue(admin, body, key);
    ResponseEntity<String> again = sessionIssue(admin, body, key);
    assertThat(first.getHeaders().containsKey(REPLAYED)).isFalse();
    assertThat(again.getHeaders().getFirst(REPLAYED)).isEqualTo("true");
    assertThat(tree(again.getBody()).get("id").asText())
        .isEqualTo(tree(first.getBody()).get("id").asText());
  }

  // ── #3 — the race: 20 identical requests, one credential, one live code ─────────────────────

  @Test
  void twentyConcurrentIdenticalRequests_issueExactlyOnce_andLeaveExactlyOneLiveCode()
      throws Exception {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("idem-race");
    ClientKey client = createClient(admin, List.of(schemaId));
    Map<String, Object> body = mintBody(schemaId, ref());
    String key = "race-" + UUID.randomUUID();

    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger conflicts = new AtomicInteger();
    List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                start.await();
                for (int attempt = 0; attempt < 50; attempt++) {
                  ResponseEntity<String> response = m2mIssue(client.apiKey(), body, key);
                  if (response.getStatusCode() != HttpStatus.CONFLICT) {
                    return response;
                  }
                  conflicts.incrementAndGet();
                  assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                      .isEqualTo("2");
                  Thread.sleep(50);
                }
                throw new AssertionError("still 409 after 50 retries");
              }));
    }
    start.countDown();
    List<ResponseEntity<String>> responses = new ArrayList<>();
    for (Future<ResponseEntity<String>> future : futures) {
      responses.add(future.get(120, TimeUnit.SECONDS));
    }
    pool.shutdown();

    assertThat(responses)
        .allSatisfy(r -> assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK));
    long fresh = responses.stream().filter(r -> !r.getHeaders().containsKey(REPLAYED)).count();
    assertThat(fresh).as("exactly one response is the fresh issuance").isEqualTo(1);
    Set<String> ids = ConcurrentHashMap.newKeySet();
    List<String> codes = new ArrayList<>();
    for (ResponseEntity<String> r : responses) {
      JsonNode json = tree(r.getBody());
      ids.add(json.get("id").asText());
      if (json.hasNonNull("claimCode")) {
        codes.add(json.get("claimCode").asText());
      }
    }
    assertThat(ids).as("every response names the same credential").hasSize(1);
    String credentialId = ids.iterator().next();
    assertThat(
            count("SELECT COUNT(*) FROM credential WHERE issuer_client_id = ?::uuid", client.id()))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM claim_code WHERE credential_id = ?::uuid", credentialId))
        .as("replays reissue the one claim_code row in place, never add rows")
        .isEqualTo(1);
    byte[] storedHash =
        inDefaultTenant(
            () ->
                jdbc.queryForObject(
                    "SELECT code_hash FROM claim_code WHERE credential_id = ?::uuid",
                    byte[].class,
                    credentialId));
    assertThat(codes.stream().filter(code -> Arrays.equals(sha256(code), storedHash)).count())
        .as("of all the codes handed out, exactly one is still live")
        .isEqualTo(1);
  }

  // ── #4 — replay before the claim: a new code, the old one dies, disclosures untouched ──────

  @Test
  void replayBeforeClaim_reissuesTheCodeInPlace_oldCodeDies_newCodeRedeems() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("idem-reissue");
    ClientKey client = createClient(admin, List.of(schemaId));
    Map<String, Object> body = mintBody(schemaId, ref());
    String key = "reissue-" + UUID.randomUUID();

    JsonNode first = tree(m2mIssue(client.apiKey(), body, key).getBody());
    String credentialId = first.get("id").asText();
    String oldCode = first.get("claimCode").asText();
    assertThat(first.get("claimCodeExpiresAt").isNull()).isFalse();
    assertThat(first.get("claimCodeReissued").asBoolean()).isFalse();
    byte[] disclosuresBefore = disclosures(credentialId);

    ResponseEntity<String> replay = m2mIssue(client.apiKey(), body, key);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getHeaders().getFirst(REPLAYED)).isEqualTo("true");
    JsonNode replayed = tree(replay.getBody());
    String newCode = replayed.get("claimCode").asText();
    assertThat(newCode).isNotBlank().isNotEqualTo(oldCode);
    assertThat(replayed.get("claimCodeReissued").asBoolean()).isTrue();
    assertThat(replayed.get("claimed").asBoolean()).isFalse();
    assertThat(replayed.get("sdJwt").isNull()).isTrue();
    assertThat(replayed.get("id").asText()).isEqualTo(credentialId);
    assertThat(disclosures(credentialId))
        .as("disclosures_enc bytes-equal")
        .isEqualTo(disclosuresBefore);

    assertRedeem(oldCode, HttpStatus.NOT_FOUND, "KH-CLM-0404");
    assertRedeem(newCode, HttpStatus.OK, null);
  }

  // ── #5 — replay after the claim: claimed, no code, nothing reissued ────────────────────────

  @Test
  void replayAfterClaim_isClaimedTrue_withNoCode() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("idem-claimed");
    ClientKey client = createClient(admin, List.of(schemaId));
    Map<String, Object> body = mintBody(schemaId, ref());
    String key = "claimed-" + UUID.randomUUID();

    JsonNode first = tree(m2mIssue(client.apiKey(), body, key).getBody());
    assertRedeem(first.get("claimCode").asText(), HttpStatus.OK, null);

    JsonNode replayed = tree(m2mIssue(client.apiKey(), body, key).getBody());
    assertThat(replayed.get("claimed").asBoolean()).isTrue();
    assertThat(replayed.get("claimCode").isNull()).isTrue();
    assertThat(replayed.get("claimCodeReissued").asBoolean()).isFalse();
    assertThat(replayed.get("deliveryLost").asBoolean()).isFalse();
    assertThat(disclosures(first.get("id").asText())).as("zeroed on claim, still zero").isNull();
  }

  // ── #6 — expired but not yet swept: reissue + extend; racing the sweeper never loses ───────

  @Test
  void replayAfterExpiryBeforeSweep_reissuesAndExtends() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("idem-expired");
    ClientKey client = createClient(admin, List.of(schemaId));
    Map<String, Object> body = mintBody(schemaId, ref());
    String key = "expired-" + UUID.randomUUID();

    JsonNode first = tree(m2mIssue(client.apiKey(), body, key).getBody());
    String credentialId = first.get("id").asText();
    expireClaimCode(credentialId);

    clock.advance(Duration.ofMinutes(5));
    JsonNode replayed = tree(m2mIssue(client.apiKey(), body, key).getBody());
    assertThat(replayed.get("claimCodeReissued").asBoolean()).isTrue();
    Instant expiresAt = Instant.parse(replayed.get("claimCodeExpiresAt").asText());
    assertThat(expiresAt).as("TTL counted from the injected clock").isAfter(clock.instant());
    assertThat(claimCodeExpiry(credentialId)).isEqualTo(expiresAt);
    clock.reset();
    assertRedeem(replayed.get("claimCode").asText(), HttpStatus.OK, null);
  }

  @Test
  void replayRacingTheExpirySweeper_neverHandsOutACodeWhoseDisclosuresAreGone() throws Exception {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("idem-sweep-race");
    ClientKey client = createClient(admin, List.of(schemaId));
    ClaimCodeExpiryWorker sweeper = new ClaimCodeExpiryWorker(claimCodes, audit, systemAccess);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    int reissuedRounds = 0;
    int lostRounds = 0;
    try {
      for (int round = 0; round < 8; round++) {
        clearRedeemThrottle();
        Map<String, Object> body = mintBody(schemaId, ref());
        String key = "sweep-race-" + round + "-" + UUID.randomUUID();
        String credentialId =
            tree(m2mIssue(client.apiKey(), body, key).getBody()).get("id").asText();
        expireClaimCode(credentialId);

        CountDownLatch go = new CountDownLatch(1);
        Future<Integer> sweep =
            pool.submit(
                () -> {
                  go.await();
                  return sweeper.sweep();
                });
        Future<ResponseEntity<String>> replay =
            pool.submit(
                () -> {
                  go.await();
                  return m2mIssue(client.apiKey(), body, key);
                });
        go.countDown();
        sweep.get(60, TimeUnit.SECONDS);
        JsonNode replayed = tree(replay.get(60, TimeUnit.SECONDS).getBody());

        if (replayed.hasNonNull("claimCode")) {
          reissuedRounds++;
          assertThat(disclosures(credentialId))
              .as("round %d: a handed-out code must still have its disclosures", round)
              .isNotNull();
          assertRedeem(replayed.get("claimCode").asText(), HttpStatus.OK, null);
        } else {
          lostRounds++;
          assertThat(replayed.get("deliveryLost").asBoolean()).as("round %d", round).isTrue();
          assertThat(disclosures(credentialId)).isNull();
        }
      }
    } finally {
      pool.shutdown();
    }
    assertThat(reissuedRounds + lostRounds).isEqualTo(8);
  }

  // ── #7 — canonical body hash ───────────────────────────────────────────────────────────────

  @Test
  void sameKeyDifferentBody_isKhIdem0422_butKeyOrderAndNullsDoNotMatter() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("idem-hash");
    ClientKey client = createClient(admin, List.of(schemaId));
    String holderRef = ref();
    String key = "hash-" + UUID.randomUUID();

    String original =
        "{\"schemaId\":\""
            + schemaId
            + "\",\"holderRef\":\""
            + holderRef
            + "\",\"maxUses\":1,\"claims\":{\"field\":\"v\",\"other\":\"w\"}}";
    String reordered =
        "{\"claims\":{\"other\":\"w\",\"field\":\"v\"},\"validMinutes\":null,\"maxUses\":1,"
            + "\"sdFields\":null,\"holderRef\":\""
            + holderRef
            + "\",\"schemaId\":\""
            + schemaId
            + "\",\"mintClaimCode\":null}";
    String different =
        "{\"schemaId\":\""
            + schemaId
            + "\",\"holderRef\":\""
            + holderRef
            + "\",\"maxUses\":1,\"claims\":{\"field\":\"CHANGED\",\"other\":\"w\"}}";

    ResponseEntity<String> first = rawM2m(ISSUE, client.apiKey(), original, key);
    assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.OK);
    ResponseEntity<String> same = rawM2m(ISSUE, client.apiKey(), reordered, key);
    assertThat(same.getHeaders().getFirst(REPLAYED)).isEqualTo("true");
    assertThat(tree(same.getBody()).get("id").asText())
        .isEqualTo(tree(first.getBody()).get("id").asText());

    ResponseEntity<String> reused = rawM2m(ISSUE, client.apiKey(), different, key);
    assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(tree(reused.getBody()).get("code").asText()).isEqualTo("KH-IDEM-0422");
    assertThat(
            count("SELECT COUNT(*) FROM credential WHERE issuer_client_id = ?::uuid", client.id()))
        .isEqualTo(1);
  }

  // ── #8 — direct sdJwt mode cannot be replayed ─────────────────────────────────────────────

  @Test
  void directModeReplay_isSdJwtNull_deliveryLostTrue_andAudited() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("idem-direct");
    ClientKey client = createClient(admin, List.of(schemaId));
    Map<String, Object> body = issueBody(schemaId, ref());
    String key = "direct-" + UUID.randomUUID();

    JsonNode first = tree(m2mIssue(client.apiKey(), body, key).getBody());
    assertThat(first.get("sdJwt").asText()).isNotBlank();
    assertThat(first.get("claimCode").isNull()).isTrue();

    JsonNode replayed = tree(m2mIssue(client.apiKey(), body, key).getBody());
    assertThat(replayed.get("sdJwt").isNull()).isTrue();
    assertThat(replayed.get("deliveryLost").asBoolean()).isTrue();
    assertThat(replayed.get("claimed").asBoolean()).isFalse();

    String ref = first.get("ref").asText();
    String detail =
        inDefaultTenant(
            () ->
                jdbc.queryForObject(
                    "SELECT detail::text FROM audit_log WHERE action = 'ISSUANCE_REPLAYED'"
                        + " AND entity_ref = ?",
                    String.class,
                    ref));
    JsonNode auditDetail = tree(detail);
    assertThat(auditDetail.get("deliveryLost").asBoolean()).isTrue();
    assertThat(auditDetail.get("claimCodeReissued").asBoolean()).isFalse();
    assertThat(auditDetail.get("scope").asText()).isEqualTo("ISSUE");
    assertThat(auditDetail.get("keySha256").asText())
        .isEqualTo(HexFormat.of().formatHex(sha256(key)));
    assertThat(detail).doesNotContain(key);
  }

  // ── #9 — bulk: same results, fresh codes for unclaimed rows, a code-free snapshot ──────────

  @Test
  void bulkReplay_returnsTheSameResults_reissuesUnclaimedCodes_andStoresNoCode() {
    AuthenticatedSession admin = adminSession();
    PublishedSchema schema = publishedSchemaWithCode("idem-bulk");
    ClientKey client = createClient(admin, List.of(schema.id()));
    String key = "bulk-" + UUID.randomUUID();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaCode", schema.code());
    body.put("mintClaimCodes", true);
    body.put(
        "items",
        List.of(
            Map.of("claims", Map.of("field", "a"), "pseudoRef", ref()),
            Map.of("claims", Map.of("field", "b"), "pseudoRef", ref()),
            Map.of("claims", Map.of("field", "c"), "pseudoRef", "not-a-holder-ref")));

    ResponseEntity<String> firstResponse = m2mBulk(client.apiKey(), body, key);
    assertThat(firstResponse.getStatusCode()).as(firstResponse.getBody()).isEqualTo(HttpStatus.OK);
    JsonNode first = tree(firstResponse.getBody()).get("results");
    assertThat(first.get(2).get("status").asText()).isEqualTo("FAILED");
    String claimedCode = first.get(0).get("claimCode").asText();
    String unclaimedCode = first.get(1).get("claimCode").asText();
    assertRedeem(claimedCode, HttpStatus.OK, null);

    ResponseEntity<String> replayResponse = m2mBulk(client.apiKey(), body, key);
    assertThat(replayResponse.getHeaders().getFirst(REPLAYED)).isEqualTo("true");
    JsonNode replay = tree(replayResponse.getBody());
    assertThat(replay.get("total").asInt()).isEqualTo(3);
    assertThat(replay.get("succeeded").asInt()).isEqualTo(2);
    assertThat(replay.get("failed").asInt()).isEqualTo(1);
    JsonNode results = replay.get("results");
    for (int i = 0; i < 3; i++) {
      assertThat(results.get(i).get("index").asInt()).isEqualTo(i);
      assertThat(results.get(i).get("status").asText())
          .isEqualTo(first.get(i).get("status").asText());
      assertThat(results.get(i).get("id")).isEqualTo(first.get(i).get("id"));
      assertThat(results.get(i).get("ref")).isEqualTo(first.get(i).get("ref"));
      assertThat(results.get(i).get("holderRef")).isEqualTo(first.get(i).get("holderRef"));
    }
    assertThat(results.get(0).get("claimed").asBoolean()).isTrue();
    assertThat(results.get(0).get("claimCode").isNull()).isTrue();
    String reissued = results.get(1).get("claimCode").asText();
    assertThat(reissued).isNotBlank().isNotEqualTo(unclaimedCode);
    assertThat(results.get(2).get("error").get("code").asText())
        .isEqualTo(first.get(2).get("error").get("code").asText())
        .isEqualTo("KH-ISS-0400");
    assertRedeem(unclaimedCode, HttpStatus.NOT_FOUND, "KH-CLM-0404");
    assertRedeem(reissued, HttpStatus.OK, null);
    assertThat(
            count("SELECT COUNT(*) FROM credential WHERE issuer_client_id = ?::uuid", client.id()))
        .as("nothing issued twice")
        .isEqualTo(2);

    String snapshot =
        inDefaultTenant(
            () ->
                jdbc.queryForObject(
                    "SELECT result_snapshot::text FROM issuance_idempotency"
                        + " WHERE idempotency_key = ?",
                    String.class,
                    key));
    assertThat(snapshot)
        .doesNotContain(claimedCode, unclaimedCode, reissued)
        .doesNotContain(first.get(0).get("holderRef").asText())
        .doesNotContainIgnoringCase("claimCode");
  }

  @Test
  void bulkTwinWhileTheBatchIsStillRunning_isKhIdem0409_withRetryAfter() {
    AuthenticatedSession admin = adminSession();
    PublishedSchema schema = publishedSchemaWithCode("idem-bulk-409");
    ClientKey client = createClient(admin, List.of(schema.id()));
    String key = "bulk-409-" + UUID.randomUUID();
    Map<String, Object> body = bulkBody(schema.code(), true);
    // A batch that claimed the key and is still issuing rows (its IN_PROGRESS row is committed).
    inDefaultTenant(
        () ->
            guard.claim(
                IssuanceIdempotency.Scope.BULK,
                key,
                guard.requestHash(body),
                UUID.fromString(client.id())));

    ResponseEntity<String> twin = m2mBulk(client.apiKey(), body, key);
    assertThat(twin.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(tree(twin.getBody()).get("code").asText()).isEqualTo("KH-IDEM-0409");
    assertThat(twin.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
  }

  // ── #10 — V1-b: holderRef optional for human sessions only ─────────────────────────────────

  @Test
  void humanSessionWithoutHolderRef_getsAGeneratedOne_machineDoesNot() {
    AuthenticatedSession admin = adminSession();
    PublishedSchema schema = publishedSchemaWithCode("idem-v1");
    ClientKey client = createClient(admin, List.of(schema.id()));

    Map<String, Object> noRef = issueBody(schema.id(), ref());
    noRef.remove("holderRef");
    ResponseEntity<String> human = SessionTestSupport.post(rest, ISSUE, admin, noRef);
    assertThat(human.getStatusCode()).as(human.getBody()).isEqualTo(HttpStatus.OK);
    String generated = tree(human.getBody()).get("holderRef").asText();
    assertThat(generated).matches("^[0-9a-f]{64}$");
    assertThat(count("SELECT COUNT(*) FROM holder WHERE pseudo_ref = ?", generated)).isEqualTo(1);

    ResponseEntity<String> machine = m2mIssue(client.apiKey(), noRef);
    assertThat(machine.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(tree(machine.getBody()).get("code").asText()).isEqualTo("KH-ISS-0400");

    // M2M echoes what it sent.
    String sent = ref();
    ResponseEntity<String> echoed = m2mIssue(client.apiKey(), issueBody(schema.id(), sent));
    assertThat(tree(echoed.getBody()).get("holderRef").asText()).isEqualTo(sent);

    // Bulk from a session: rows without pseudoRef issue, each with its own reference.
    Map<String, Object> bulk = new LinkedHashMap<>();
    bulk.put("schemaCode", schema.code());
    bulk.put(
        "items",
        List.of(Map.of("claims", Map.of("field", "a")), Map.of("claims", Map.of("field", "b"))));
    ResponseEntity<String> bulkResponse = SessionTestSupport.post(rest, BULK, admin, bulk);
    JsonNode rows = tree(bulkResponse.getBody()).get("results");
    assertThat(rows.get(0).get("status").asText()).isEqualTo("ISSUED");
    assertThat(rows.get(1).get("status").asText()).isEqualTo("ISSUED");
    String first = rows.get(0).get("holderRef").asText();
    String second = rows.get(1).get("holderRef").asText();
    assertThat(first).matches("^[0-9a-f]{64}$");
    assertThat(second).matches("^[0-9a-f]{64}$").isNotEqualTo(first);
  }

  // ── #11 — D-CC: mintClaimCode over M2M ────────────────────────────────────────────────────

  @Test
  void mintClaimCodeOverM2m_returnsARedeemableCode_inTheIssuingResponse() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("idem-mint");
    ClientKey client = createClient(admin, List.of(schemaId));

    ResponseEntity<String> response = m2mIssue(client.apiKey(), mintBody(schemaId, ref()));
    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    JsonNode json = tree(response.getBody());
    assertThat(json.get("claimCode").asText()).isNotBlank();
    assertThat(Instant.parse(json.get("claimCodeExpiresAt").asText())).isAfter(Instant.now());
    assertThat(json.get("sdJwt").asText()).as("unchanged: still handed over once").isNotBlank();
    assertRedeem(json.get("claimCode").asText(), HttpStatus.OK, null);
  }

  // ── #12 — retention sweeper ───────────────────────────────────────────────────────────────

  @Test
  void retentionSweeper_deletesOnlyExpiredKeys() {
    AuthenticatedSession admin = adminSession();
    String schemaId = publishedSchemaInDefaultTenant("idem-retention");
    ClientKey client = createClient(admin, List.of(schemaId));
    String expiredKey = "retention-old-" + UUID.randomUUID();
    String liveKey = "retention-new-" + UUID.randomUUID();
    m2mIssue(client.apiKey(), issueBody(schemaId, ref()), expiredKey);
    m2mIssue(client.apiKey(), issueBody(schemaId, ref()), liveKey);
    inDefaultTenant(
        () ->
            jdbc.update(
                "UPDATE issuance_idempotency SET expires_at = now() - interval '1 day'"
                    + " WHERE idempotency_key = ?",
                expiredKey));

    IdempotencyRetentionSweeper sweeper =
        new IdempotencyRetentionSweeper(idempotencyRows, systemAccess, clock);
    assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);
    assertThat(keyRows(expiredKey)).isZero();
    assertThat(keyRows(liveKey)).as("still inside its 30-day retention").isEqualTo(1);

    clock.advance(Duration.ofDays(31));
    sweeper.sweep();
    assertThat(keyRows(liveKey)).as("gone once the injected clock passes 30 days").isZero();
  }

  // ── #13 — no raw key, code, or holderRef in any log line ──────────────────────────────────

  @Test
  void issueReplayAndBulk_neverLogTheKey_aCode_orTheHolderRef() {
    AuthenticatedSession admin = adminSession();
    PublishedSchema schema = publishedSchemaWithCode("idem-logs");
    ClientKey client = createClient(admin, List.of(schema.id()));
    String holderRef = ref();
    String key = "log-probe-" + UUID.randomUUID();
    String bulkKey = "log-probe-bulk-" + UUID.randomUUID();

    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    Logger platform = (Logger) LoggerFactory.getLogger("sy.khatm");
    Level previous = platform.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    platform.setLevel(Level.DEBUG);
    List<String> secrets = new ArrayList<>(List.of(key, bulkKey, holderRef));
    try {
      Map<String, Object> body = mintBody(schema.id(), holderRef);
      secrets.add(tree(m2mIssue(client.apiKey(), body, key).getBody()).get("claimCode").asText());
      secrets.add(tree(m2mIssue(client.apiKey(), body, key).getBody()).get("claimCode").asText());
      m2mIssue(client.apiKey(), issueBody(schema.id(), holderRef), key); // 422
      Map<String, Object> bulk = bulkBody(schema.code(), true);
      for (int i = 0; i < 2; i++) {
        JsonNode results = tree(m2mBulk(client.apiKey(), bulk, bulkKey).getBody()).get("results");
        results.forEach(r -> secrets.add(r.get("claimCode").asText()));
      }
    } finally {
      root.detachAppender(appender);
      platform.setLevel(previous);
    }
    List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(lines).isNotEmpty();
    for (String secret : secrets) {
      assertThat(lines).noneMatch(line -> line.contains(secret));
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────

  private static String ref() {
    return validHolderRef();
  }

  private static Map<String, Object> mintBody(String schemaId, String holderRef) {
    Map<String, Object> body = issueBody(schemaId, holderRef);
    body.put("mintClaimCode", true);
    return body;
  }

  private static Map<String, Object> bulkBody(String schemaCode, boolean mint) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaCode", schemaCode);
    body.put("mintClaimCodes", mint);
    body.put("items", List.of(Map.of("claims", Map.of("field", "x"), "pseudoRef", ref())));
    return body;
  }

  private static void assertIdem0400(ResponseEntity<String> response) {
    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(tree(response.getBody()).get("code").asText()).isEqualTo("KH-IDEM-0400");
  }

  private ResponseEntity<String> sessionIssue(
      AuthenticatedSession session, Map<String, Object> body, String key) {
    HttpEntity<Map<String, Object>> base = session.writeHeaders(body);
    HttpHeaders headers = new HttpHeaders();
    headers.putAll(base.getHeaders());
    headers.set("Idempotency-Key", key);
    ResponseEntity<String> response =
        rest.exchange(ISSUE, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    return response;
  }

  private ResponseEntity<String> rawM2m(String path, String rawKey, String json, String key) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
    headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
    headers.set("Idempotency-Key", key);
    return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
  }

  private void assertRedeem(String code, HttpStatus expected, String expectedCode) {
    ResponseEntity<String> response =
        rest.postForEntity(REDEEM, Map.of("code", code), String.class);
    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(expected);
    if (expectedCode != null) {
      assertThat(tree(response.getBody()).get("code").asText()).isEqualTo(expectedCode);
    }
  }

  private byte[] disclosures(String credentialId) {
    return inDefaultTenant(
        () ->
            jdbc.queryForObject(
                "SELECT disclosures_enc FROM claim_code WHERE credential_id = ?::uuid",
                byte[].class,
                credentialId));
  }

  private Instant claimCodeExpiry(String credentialId) {
    return inDefaultTenant(
        () ->
            jdbc.queryForObject(
                    "SELECT expires_at FROM claim_code WHERE credential_id = ?::uuid",
                    java.sql.Timestamp.class,
                    credentialId)
                .toInstant());
  }

  private void expireClaimCode(String credentialId) {
    inDefaultTenant(
        () ->
            jdbc.update(
                "UPDATE claim_code SET expires_at = now() - interval '1 minute'"
                    + " WHERE credential_id = ?::uuid",
                credentialId));
  }

  private int keyRows(String key) {
    return count("SELECT COUNT(*) FROM issuance_idempotency WHERE idempotency_key = ?", key);
  }

  private int count(String sql, Object... args) {
    return inDefaultTenant(() -> jdbc.queryForObject(sql, Integer.class, args));
  }

  private static <T> T inDefaultTenant(Supplier<T> action) {
    TenantContext.set(TenantContext.DEFAULT_TENANT_ID, TenantContext.DEFAULT_TENANT_SLUG);
    try {
      return action.get();
    } finally {
      TenantContext.clear();
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm", e);
    }
  }
}
