package sy.khatm.platform.key.domain;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.key.persistence.IssuerKeyRepository;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-2.3 D2 — the mandatory race test for the rotate endpoint (docs/CONVENTIONS.md §11's "a PR
 * touching core invariant logic must include its concurrency test in the same PR," joining the
 * {@code ConcurrentConsumeTest}/{@code ConcurrentLastAdminTest} family): two-or-more concurrent
 * {@code rotate()} calls against the same tenant must produce exactly one success, every loser
 * failing outright (a unique-constraint violation on the {@code issuer_key_one_active} partial
 * index) rather than silently leaving two {@code ACTIVE} rows.
 *
 * <p>Lives in {@code key.domain} (not {@code db}, unlike its siblings) because {@link
 * KeyLifecycleService#rotate} is called directly here, mirroring {@code KeyLifecycleServiceTest}'s
 * own placement in this same package.
 */
class ConcurrentRotationTest extends IntegrationTestSupport {

  private static final int CONCURRENT_CALLERS = 10;

  @Autowired private KeyLifecycleService lifecycle;
  @Autowired private IssuerKeyRepository repository;

  @Test
  void rotate_tenConcurrentCallers_exactlyOneSucceeds_exactlyOneActiveKeyAfterwards()
      throws Exception {
    UUID tenantId = TenantContext.current();
    String tenantSlug = TenantContext.currentSlug();

    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLERS);
    CountDownLatch ready = new CountDownLatch(CONCURRENT_CALLERS);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();

    try {
      List<Future<Void>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_CALLERS; i++) {
        Callable<Void> task =
            () -> {
              ready.countDown();
              start.await();
              try {
                lifecycle.rotate(tenantId, tenantSlug);
                successes.incrementAndGet();
              } catch (RuntimeException e) {
                failures.incrementAndGet();
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

    assertThat(successes.get()).as("exactly one concurrent rotation must succeed").isEqualTo(1);
    assertThat(failures.get()).isEqualTo(CONCURRENT_CALLERS - 1);

    List<IssuerKey> activeRows = repository.findByTenantIdAndStateIn(tenantId, List.of("ACTIVE"));
    assertThat(activeRows).as("exactly one ACTIVE key must remain").hasSize(1);
  }
}
