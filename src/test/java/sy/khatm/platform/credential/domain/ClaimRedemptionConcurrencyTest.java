package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.persistence.ClaimCodeRepository;
import sy.khatm.platform.credential.worker.ClaimCodeExpiryWorker;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-1.2.1 DoD 4 — the {@code SELECT ... FOR UPDATE} lock ({@code
 * ClaimCodeRepository#findByCodeHashForUpdate}) is what makes redemption a genuine single-shot
 * invariant, the same way {@code CredentialRepository#consumeOne}'s atomic UPDATE is for
 * consumption ({@code db.ConcurrentConsumeTest}). Both tests here launch real, separate threads
 * racing against the same underlying database connection pool (not sequential calls dressed up as
 * concurrent) — {@code CredentialService}/{@code ClaimRedemptionService} are Spring-proxied
 * {@code @Transactional} beans, so each thread's call opens its own physical
 * transaction/connection.
 */
class ClaimRedemptionConcurrencyTest extends IntegrationTestSupport {

  private static final int CONCURRENT_CALLERS = 20;

  @Autowired private CredentialService credentialService;
  @Autowired private ClaimRedemptionService redemptionService;
  @Autowired private ClaimCodeRepository claimCodes;
  @Autowired private AuditService auditService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void redeem_twentyConcurrentCallersSameCode_exactlyOneSucceeds() throws Exception {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "ConcurrentRedeem/v1",
                "holder-concurrent-redeem",
                1,
                60,
                Map.of("result", "X"),
                List.of()));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));

    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLERS);
    CountDownLatch ready = new CountDownLatch(CONCURRENT_CALLERS);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger genericNotFound = new AtomicInteger();

    try {
      List<Future<Void>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_CALLERS; i++) {
        Callable<Void> task =
            () -> {
              ready.countDown();
              start.await();
              try {
                redemptionService.redeem(claimCode.code());
                successes.incrementAndGet();
              } catch (NotFoundException e) {
                if ("KH-CLM-0404".equals(e.errorCode().code())) {
                  genericNotFound.incrementAndGet();
                }
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
    assertThat(genericNotFound.get()).isEqualTo(CONCURRENT_CALLERS - 1);

    UUID credentialId = UUID.fromString(issued.id());
    Boolean disclosuresNull =
        jdbc.queryForObject(
            "SELECT disclosures_enc IS NULL FROM claim_code WHERE credential_id = ?",
            Boolean.class,
            credentialId);
    assertThat(disclosuresNull).isTrue();
  }

  /**
   * A race between an in-flight redeem and a concurrent expiry sweep touching the same row must
   * never deliver already-zeroed material and must never let the code be claimed at all once it has
   * genuinely expired — regardless of which side's statement reaches the row's lock first. Both
   * threads race for real (latch-synchronized start against the shared row lock); the outcome is
   * deterministic by design (an expired code can never be successfully redeemed), which is exactly
   * the invariant under test — not an artifact of the threads never actually overlapping.
   */
  @Test
  void redeemVsExpirySweep_concurrentRace_neverDeliversAndAlwaysEndsZeroed() throws Exception {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "RedeemSweepRace/v1",
                "holder-redeem-sweep-race",
                1,
                60,
                Map.of("result", "X"),
                List.of()));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));
    jdbc.update(
        "UPDATE claim_code SET expires_at = ? WHERE credential_id = ?",
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        UUID.fromString(issued.id()));

    ClaimCodeExpiryWorker worker = new ClaimCodeExpiryWorker(claimCodes, auditService);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger redeemSuccesses = new AtomicInteger();

    Callable<Void> redeemTask =
        () -> {
          ready.countDown();
          start.await();
          try {
            redemptionService.redeem(claimCode.code());
            redeemSuccesses.incrementAndGet();
          } catch (NotFoundException expected) {
            // Expected — the code is already expired, regardless of the sweep's timing.
          }
          return null;
        };
    // worker.sweep() runs on a manually-`new`'d (not Spring-managed) ClaimCodeExpiryWorker, so its
    // own @Transactional annotation gets no AOP interception on this thread — wrap it in an
    // explicit transaction here instead. (A single-threaded sibling test can get away with
    // marking the whole test method @Transactional; this one genuinely can't — the setup rows
    // must be really committed before two separate connections race to see them.)
    TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);
    Callable<Void> sweepTask =
        () -> {
          ready.countDown();
          start.await();
          transactionTemplate.executeWithoutResult(status -> worker.sweep());
          return null;
        };

    try {
      Future<Void> redeemFuture = pool.submit(redeemTask);
      Future<Void> sweepFuture = pool.submit(sweepTask);
      ready.await(10, TimeUnit.SECONDS);
      start.countDown();
      redeemFuture.get(30, TimeUnit.SECONDS);
      sweepFuture.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdown();
    }

    assertThat(redeemSuccesses.get())
        .as("an already-expired code must never be successfully redeemed, no matter the race")
        .isZero();

    UUID credentialId = UUID.fromString(issued.id());
    Boolean disclosuresNull =
        jdbc.queryForObject(
            "SELECT disclosures_enc IS NULL FROM claim_code WHERE credential_id = ?",
            Boolean.class,
            credentialId);
    Boolean claimedAtSet =
        jdbc.queryForObject(
            "SELECT claimed_at IS NOT NULL FROM claim_code WHERE credential_id = ?",
            Boolean.class,
            credentialId);
    assertThat(disclosuresNull)
        .as("the sweep must have zeroed it, since a failed redeem never writes")
        .isTrue();
    assertThat(claimedAtSet).as("never claimed").isFalse();
  }
}
