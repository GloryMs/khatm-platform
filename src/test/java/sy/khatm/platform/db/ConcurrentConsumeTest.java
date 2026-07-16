package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
}
