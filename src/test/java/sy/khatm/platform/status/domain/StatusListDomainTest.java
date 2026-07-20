package sy.khatm.platform.status.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.status.api.StatusAllocation;
import sy.khatm.platform.status.api.StatusListAllocator;
import sy.khatm.platform.status.api.StatusListLookup;
import sy.khatm.platform.status.api.StatusListRef;
import sy.khatm.platform.status.api.StatusListRevoker;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-1.3 DoD #1 (atomic bit-flip + version bump, resolvable via lookup) and DoD #5 (two
 * concurrent revokes on the same list, different bit indexes, never lose an update) at the service
 * level, plus the {@link BitstringCodec} unit contract. Lives in this package so it can reach the
 * package-private codec directly.
 */
class StatusListDomainTest extends IntegrationTestSupport {

  @Autowired private StatusListAllocator allocator;
  @Autowired private StatusListRevoker revoker;
  @Autowired private StatusListLookup lookup;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void bitstringCodec_flipThenIsSet_roundTripsAndLeavesOthersClear() {
    byte[] bitstring = readBitstring(allocator.allocate(uniqueListCode()).statusListId());

    byte[] flipped = BitstringCodec.flipBit(BitstringCodec.flipBit(bitstring, 0), 5);

    assertThat(BitstringCodec.isSet(flipped, 0)).isTrue();
    assertThat(BitstringCodec.isSet(flipped, 5)).isTrue();
    assertThat(BitstringCodec.isSet(flipped, 1)).isFalse();
    assertThat(BitstringCodec.isSet(flipped, 6)).isFalse();
  }

  @Test
  void revoke_flipsBit_andBumpsVersion_andResolvesViaLookup() {
    String listCode = uniqueListCode();
    StatusAllocation a = allocator.allocate(listCode);
    // The allocator bumps `version` on each allocation too (KH-0.2.1 behavior), so the expected
    // post-revoke version is one more than whatever allocate left it at — assert the delta, not a
    // hardcoded absolute value.
    long versionAfterAllocate = dbVersion(a.statusListId());
    long expectedVersion = versionAfterAllocate + 1;

    StatusListRef ref = revoker.revoke(a.statusListId(), a.idx());

    assertThat(ref.version()).isEqualTo(expectedVersion);
    assertThat(ref.uri()).endsWith("/sl/khatm-default/" + listCode);

    assertThat(BitstringCodec.isSet(readBitstring(a.statusListId()), a.idx())).isTrue();
    assertThat(dbVersion(a.statusListId())).isEqualTo(expectedVersion);

    StatusListRef lookedUp = lookup.findRef(a.statusListId()).orElseThrow();
    assertThat(lookedUp.version()).isEqualTo(expectedVersion);
    assertThat(lookedUp.uri()).isEqualTo(ref.uri());
  }

  /**
   * DoD #5 — two genuinely concurrent revokes (latch-synchronized start, real separate threads and
   * transactions) on the same list at different bit indexes never lose an update: the {@code FOR
   * UPDATE} lock serializes them, both bits end up set, and the version advances by exactly 2.
   */
  @Test
  void revoke_twoConcurrentOnSameList_neitherUpdateLost() throws Exception {
    // Allocate two distinct indexes on one list (a fresh listCode gives one shared list).
    String listCode = uniqueListCode();
    StatusAllocation a0 = allocator.allocate(listCode);
    UUID listId = a0.statusListId();
    StatusAllocation a1 = allocator.allocate(listCode); // same list → next sequential idx
    int idx0 = a0.idx();
    int idx1 = a1.idx();
    assertThat(idx1).isNotEqualTo(idx0);
    // allocate() bumps version too (KH-0.2.1 behavior) — the two revokes below add exactly 2 more
    // to whatever it left the list at, so assert the delta rather than a hardcoded absolute value.
    long versionBeforeRevokes = dbVersion(listId);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    // Each call is a Spring-proxied @Transactional, so each thread opens its own physical
    // transaction; the FOR UPDATE lock in findByIdForUpdate serializes the two.
    Callable<StatusListRef> taskA =
        () -> {
          ready.countDown();
          start.await();
          return revoker.revoke(listId, idx0);
        };
    Callable<StatusListRef> taskB =
        () -> {
          ready.countDown();
          start.await();
          return revoker.revoke(listId, idx1);
        };

    try {
      Future<StatusListRef> fa = pool.submit(taskA);
      Future<StatusListRef> fb = pool.submit(taskB);
      ready.await(10, TimeUnit.SECONDS);
      start.countDown();
      fa.get(30, TimeUnit.SECONDS);
      fb.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdown();
    }

    byte[] bitstring = readBitstring(listId);
    assertThat(BitstringCodec.isSet(bitstring, idx0)).isTrue();
    assertThat(BitstringCodec.isSet(bitstring, idx1)).isTrue();
    assertThat(dbVersion(listId))
        .as("both revokes counted — no lost update")
        .isEqualTo(versionBeforeRevokes + 2);
  }

  private String uniqueListCode() {
    return "test-list-" + UUID.randomUUID();
  }

  private long dbVersion(UUID listId) {
    return jdbc.queryForObject("SELECT version FROM status_list WHERE id = ?", Long.class, listId);
  }

  private byte[] readBitstring(UUID listId) {
    // bytea column → getBytes is the direct read; no Array wrapping needed.
    return jdbc.queryForObject(
        "SELECT bitstring FROM status_list WHERE id = ?",
        (rs, rowNum) -> rs.getBytes("bitstring"),
        listId);
  }
}
