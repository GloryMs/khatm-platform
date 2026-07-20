package sy.khatm.platform.status.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.status.api.StatusAllocation;
import sy.khatm.platform.status.api.StatusListAllocator;
import sy.khatm.platform.status.api.StatusListRef;
import sy.khatm.platform.status.api.StatusListRevoker;
import sy.khatm.platform.status.persistence.StatusListRepository;
import sy.khatm.platform.status.worker.StatusListPublishSweepWorker;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-1.3 DoD #2 (the worker publishes a fresh JWS — {@code signed_artifact} set, {@code
 * artifact_version == version}, one {@code STATUS_LIST_PUBLISHED} audit row), DoD #4 (a revocation
 * storm collapses to a single republish whose final artifact reflects every flipped bit), and the
 * debounce/idempotency + sweep-catch-up properties (D5). Lives in this package to reach the
 * package-private {@link BitstringCodec} and to construct the worker directly.
 */
class StatusListPublishTest extends IntegrationTestSupport {

  @Autowired private StatusListAllocator allocator;
  @Autowired private StatusListRevoker revoker;
  @Autowired private StatusListPublisher publisher;
  @Autowired private StatusListRepository statusLists;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void publishIfStale_signsAndStoresArtifact_andAudits_andIsIdempotent() throws Exception {
    StatusAllocation a = allocator.allocate(uniqueListCode());
    StatusListRef ref = revoker.revoke(a.statusListId(), a.idx());
    long expectedVersion =
        ref.version(); // allocate() itself also bumps version — trust the live one

    boolean published = publisher.publishIfStale(a.statusListId());

    assertThat(published).isTrue();
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT signed_artifact, artifact_version FROM status_list WHERE id = ?",
            a.statusListId());
    String artifact = (String) row.get("signed_artifact");
    assertThat(artifact).isNotNull();
    assertThat(((Number) row.get("artifact_version")).longValue()).isEqualTo(expectedVersion);
    assertThat(auditCount(a.statusListId())).isEqualTo(1);

    // The artifact is a compact JWS carrying the spec D1 claims; `bits` is the base64url-encoded
    // gzip-compressed bitstring verbatim (StatusListPublisher does no extra compression step), so
    // BitstringCodec.isSet — which inflates internally — reads it directly, no separate gunzip.
    JWTClaimsSet claims = SignedJWT.parse(artifact).getJWTClaimsSet();
    assertThat(claims.getLongClaim("ver")).isEqualTo(expectedVersion);
    assertThat(claims.getStringClaim("list")).isNotNull();
    assertThat(claims.getLongClaim("cap")).isPositive();
    byte[] gzippedBits = decodeB64Url(claims.getStringClaim("bits"));
    assertThat(BitstringCodec.isSet(gzippedBits, a.idx())).isTrue();

    // Idempotent: a second call on an already-current list is a no-op — no new audit row.
    boolean republished = publisher.publishIfStale(a.statusListId());
    assertThat(republished).isFalse();
    assertThat(auditCount(a.statusListId())).isEqualTo(1);
  }

  /**
   * DoD #4 — 25 rapid revokes on the same list, then a single publish, yields one publish (one
   * audit row) whose artifact reflects all 25 flipped bits at the final version. This is D5's
   * debounce in its essential form: {@code publishIfStale} re-reads the live version, so every
   * intermediate dispatch after the first catch-up finds nothing left to do.
   */
  @Test
  void publishIfStale_afterRevocationStorm_publishesOnceWithAllBitsSet() throws Exception {
    String listCode = uniqueListCode();
    StatusAllocation first = allocator.allocate(listCode);
    UUID listId = first.statusListId();
    int[] indexes = new int[25];
    indexes[0] = first.idx();
    for (int i = 1; i < 25; i++) {
      // Re-allocating the same listCode returns the same list with the next sequential index.
      indexes[i] = allocator.allocate(listCode).idx();
    }
    long expectedVersion = -1;
    for (int idx : indexes) {
      expectedVersion = revoker.revoke(listId, idx).version();
    }

    boolean published = publisher.publishIfStale(listId);

    assertThat(published).isTrue();
    assertThat(auditCount(listId)).as("one republish for the whole storm").isEqualTo(1);
    assertThat(
            ((Number)
                    jdbc.queryForMap(
                            "SELECT artifact_version FROM status_list WHERE id = ?", listId)
                        .get("artifact_version"))
                .longValue())
        .isEqualTo(expectedVersion);

    JWTClaimsSet claims =
        SignedJWT.parse(
                (String)
                    jdbc.queryForMap("SELECT signed_artifact FROM status_list WHERE id = ?", listId)
                        .get("signed_artifact"))
            .getJWTClaimsSet();
    assertThat(claims.getLongClaim("ver")).isEqualTo(expectedVersion);
    byte[] gzippedBits = decodeB64Url(claims.getStringClaim("bits"));
    for (int idx : indexes) {
      assertThat(BitstringCodec.isSet(gzippedBits, idx))
          .as("bit %d flipped in the final artifact", idx)
          .isTrue();
    }
  }

  /** The periodic sweep (the D5 safety net) catches up a list the event path never reached. */
  @Test
  void sweep_publishesStaleAndNeverPublishedLists() {
    StatusAllocation a = allocator.allocate(uniqueListCode());
    StatusListRef ref = revoker.revoke(a.statusListId(), a.idx());
    // No event handler in this (non-worker) context — the list is stale until the sweep runs.
    StatusListPublishSweepWorker sweep = new StatusListPublishSweepWorker(statusLists, publisher);

    int published = sweep.sweep();

    assertThat(published).isGreaterThanOrEqualTo(1);
    assertThat(
            ((Number)
                    jdbc.queryForMap(
                            "SELECT artifact_version FROM status_list WHERE id = ?",
                            a.statusListId())
                        .get("artifact_version"))
                .longValue())
        .isEqualTo(ref.version());
  }

  private long auditCount(UUID listId) {
    String listCode = listCodeOf(listId);
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE action = 'STATUS_LIST_PUBLISHED' AND entity_ref = ?",
        Long.class,
        listCode);
  }

  private String listCodeOf(UUID listId) {
    return jdbc.queryForObject(
        "SELECT list_code FROM status_list WHERE id = ?", String.class, listId);
  }

  private String uniqueListCode() {
    return "test-list-" + UUID.randomUUID();
  }

  private static byte[] decodeB64Url(String b64url) {
    return Base64.getUrlDecoder().decode(b64url);
  }
}
