package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.credential.api.ConsumeRequest;
import sy.khatm.platform.credential.api.ConsumeResponse;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.2 §5 acceptance criterion 3 — the atomic-consume UPDATE (a nucleus of the later, official
 * {@code ConcurrentConsumeTest} at KH-1.4.2): 50 concurrent threads racing to consume a credential
 * with {@code max_uses = 1} must produce exactly one success.
 *
 * <p>Correctness comes entirely from {@code CredentialRepository#consumeOne}'s single conditional
 * {@code UPDATE ... WHERE uses_remaining > 0} — row-level locking in Postgres serialises the
 * concurrent callers so only the first one observes {@code uses_remaining > 0} and decrements it.
 */
class ConcurrentConsumeTest extends IntegrationTestSupport {

  private static final int CONCURRENT_CALLERS = 50;

  @Autowired private CredentialService credentialService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void consume_fiftyConcurrentCallers_exactlyOneSucceeds() throws Exception {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "ConcurrentConsumeProbe/v1", "holder-concurrent-probe", 1, 60, Map.of(), null));

    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLERS);
    CountDownLatch ready = new CountDownLatch(CONCURRENT_CALLERS);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();

    try {
      List<Future<Void>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_CALLERS; i++) {
        int callerIndex = i;
        Callable<Void> task =
            () -> {
              ready.countDown();
              start.await();
              ConsumeResponse response =
                  credentialService.consume(
                      new ConsumeRequest(issued.id(), "consumer-" + callerIndex, null));
              if (response.consumed()) {
                successes.incrementAndGet();
              }
              return null;
            };
        futures.add(pool.submit(task));
      }

      ready.await(10, TimeUnit.SECONDS);
      start.countDown();
      for (Future<Void> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdown();
    }

    assertThat(successes.get()).isEqualTo(1);
  }

  /**
   * Spec FS-1.6 D1 — the exactly-once {@code EXHAUSTED} transition, extending this class's own
   * single-use probe to {@code maxUses = N}: {@code N + 1} concurrent callers racing a credential
   * with {@code N} uses must produce exactly {@code N} successes, and the status-list bit flip +
   * {@code CREDENTIAL_EXHAUSTED} audit row {@link sy.khatm.platform.credential.domain
   * .AtomicConsumptionRecorder#tryConsume} triggers on exhaustion must each happen exactly once —
   * never once per successful consumer, never zero times.
   */
  @Test
  void consume_nPlusOneConcurrentCallersOnMaxUsesN_exactlyNSucceed_exhaustionFlipsStatusOnce()
      throws Exception {
    int maxUses = 5;
    int callers = maxUses + 1;
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "ConcurrentExhaustionProbe/v1",
                "holder-exhaustion-probe",
                maxUses,
                60,
                Map.of(),
                null));
    UUID credentialId = UUID.fromString(issued.id());
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status_list_id, version FROM credential c JOIN status_list sl ON sl.id ="
                + " c.status_list_id WHERE c.id = ?",
            credentialId);
    UUID statusListId = (UUID) row.get("status_list_id");
    long versionBeforeConsuming = ((Number) row.get("version")).longValue();

    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();

    try {
      List<Future<Void>> futures = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        int callerIndex = i;
        Callable<Void> task =
            () -> {
              ready.countDown();
              start.await();
              ConsumeResponse response =
                  credentialService.consume(
                      new ConsumeRequest(issued.id(), "exhaustion-consumer-" + callerIndex, null));
              if (response.consumed()) {
                successes.incrementAndGet();
              }
              return null;
            };
        futures.add(pool.submit(task));
      }

      ready.await(10, TimeUnit.SECONDS);
      start.countDown();
      for (Future<Void> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdown();
    }

    assertThat(successes.get()).as("exactly maxUses callers must succeed").isEqualTo(maxUses);

    int usesRemaining =
        jdbc.queryForObject(
            "SELECT uses_remaining FROM credential WHERE id = ?", Integer.class, credentialId);
    assertThat(usesRemaining).isZero();

    long exhaustedAuditRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CREDENTIAL_EXHAUSTED' AND entity_ref ="
                + " ?",
            Long.class,
            credentialId.toString());
    assertThat(exhaustedAuditRows).as("exhaustion audited exactly once").isEqualTo(1);

    long versionAfterConsuming =
        jdbc.queryForObject(
            "SELECT version FROM status_list WHERE id = ?", Long.class, statusListId);
    assertThat(versionAfterConsuming)
        .as("the status-list bit-flip must have happened exactly once")
        .isEqualTo(versionBeforeConsuming + 1);
  }
}
