package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
import sy.khatm.platform.consumer.api.ConsumingPartyRef;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-1.4.4 D6 — closes the {@code ConsumingPartyRegistryService#ensure} find-or-create race that
 * KH-1.1-BE Part C flagged (docs/STATE.md "Next up" #4). The row's id is derived deterministically
 * from {@code (tenant, code)}, so two callers racing to {@code ensure()} a brand-new code both miss
 * the initial {@code findById} and both attempt an {@code INSERT} under the identical primary key.
 *
 * <p>The fix: {@code ensure} holds no enclosing transaction and forces a true {@code INSERT} (the
 * entity is {@code Persistable}), so the loser's {@code saveAndFlush} rolls back its own
 * transaction cleanly and the follow-up re-read returns the winner's row on a clean connection.
 * Both callers get the same id; exactly one {@code consuming_party} row exists.
 */
class ConsumingPartyEnsureRaceTest extends IntegrationTestSupport {

  @Autowired private ConsumingPartyRegistry consumingParties;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void ensure_twoConcurrentCallersSameNewCode_oneRow_sameId() throws Exception {
    String code = "ensure-race-" + UUID.randomUUID();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    List<ConsumingPartyRef> results;
    try {
      List<Future<ConsumingPartyRef>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        Callable<ConsumingPartyRef> task =
            () -> {
              ready.countDown();
              start.await();
              return consumingParties.ensure(code);
            };
        futures.add(pool.submit(task));
      }

      ready.await(10, TimeUnit.SECONDS);
      start.countDown();
      results = new ArrayList<>();
      for (Future<ConsumingPartyRef> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdown();
    }

    assertThat(results).hasSize(2);
    assertThat(results.get(0).id()).isEqualTo(results.get(1).id());

    Integer rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM consuming_party WHERE code = ?", Integer.class, code);
    assertThat(rows).isEqualTo(1);
  }
}
