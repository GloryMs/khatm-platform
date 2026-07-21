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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.credential.api.ConsumeRequest;
import sy.khatm.platform.credential.api.ConsumeResponse;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.ConsumptionEvent;
import sy.khatm.platform.credential.domain.Credential;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.credential.persistence.ConsumptionEventRepository;
import sy.khatm.platform.credential.persistence.CredentialRepository;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-1.4.1/1.4.2 closure (the race {@code docs/STATE.md}'s "Next up" #3 flagged, not the
 * already-covered double-spend one {@code ConcurrentConsumeTest} proves): two real threads sharing
 * one {@code idempotencyKey}, both racing past the Redis fast-path cache (guaranteed cold here —
 * {@link IntegrationTestSupport} wires no Redis container at all, so {@code CredentialService}'s
 * {@code safeRedisGet}/{@code safeRedisSet} silently no-op on every call) and both hitting the DB
 * path. The credential starts with {@code maxUses = 2} specifically so that <em>both</em> callers'
 * atomic decrement can legitimately succeed — proving this is a double-<em>submit</em> race (the
 * same logical request, retried), not the double-<em>spend</em> race {@code ConcurrentConsumeTest}
 * already covers with {@code maxUses = 1}.
 */
class ConsumeIdempotencyRaceTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private CredentialRepository credentials;
  @Autowired private ConsumptionEventRepository consumptionEvents;
  @Autowired private ConsumingPartyRegistry consumingParties;

  @Test
  void consume_twoConcurrentCallersSameIdempotencyKey_oneEventRow_oneDecrement() throws Exception {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "IdemRaceProbe/v1", "holder-idem-race", 2, 60, Map.of("field", "value"), null));
    String idemKey = "idem-race-" + UUID.randomUUID();
    String consumerCode = "idem-race-party-" + UUID.randomUUID();
    // Pre-create the consuming party synchronously: ConsumingPartyRegistryService#ensure has its
    // own, unrelated find-or-create race (a materially different bug from the one this test
    // targets) when two callers share a brand-new code concurrently — flagged in docs/STATE.md
    // for a future session rather than fixed here, per this session's narrow Part C scope
    // (consumption_event.idempotency_key only). Pre-creating the row keeps that separate race out
    // of this test entirely.
    consumingParties.ensure(consumerCode);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    List<ConsumeResponse> results;
    try {
      List<Future<ConsumeResponse>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        Callable<ConsumeResponse> task =
            () -> {
              ready.countDown();
              start.await();
              return credentialService.consume(
                  new ConsumeRequest(issued.id(), consumerCode, idemKey));
            };
        futures.add(pool.submit(task));
      }

      ready.await(10, TimeUnit.SECONDS);
      start.countDown();
      results = new ArrayList<>();
      for (Future<ConsumeResponse> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdown();
    }

    assertThat(results).hasSize(2);
    assertThat(results).allMatch(ConsumeResponse::consumed);

    Credential reloaded = credentials.findById(UUID.fromString(issued.id())).orElseThrow();
    assertThat(reloaded.getUsesRemaining()).isEqualTo(1);

    ConsumptionEvent event = consumptionEvents.findByIdempotencyKey(idemKey).orElseThrow();
    assertThat(event.getCredentialId()).isEqualTo(UUID.fromString(issued.id()));
  }
}
