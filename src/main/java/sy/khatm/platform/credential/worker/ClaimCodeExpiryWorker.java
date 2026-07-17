package sy.khatm.platform.credential.worker;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.persistence.ClaimCodeRepository;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Periodically zeros {@code claim_code.disclosures_enc} for codes whose TTL has elapsed — the
 * expiry half of FS-0.2 §3.7's P1 zeroing contract (the on-claim half, zeroing the moment a wallet
 * successfully claims, belongs to the claim-delivery endpoint KH-1.2.1). This worker guarantees
 * that even an unclaimed, expired code cannot leave encrypted disclosures sitting in the database
 * forever — after it runs, the platform holds proofs only.
 *
 * <p>Worker-role only ({@code khatm.worker.enabled=true}). It runs in the {@code worker} runtime
 * image, never in {@code api} (ADR-09). The sweep interval is configurable via {@code
 * khatm.worker.claim-code.expiry-sweep-ms} (default 300000 = 5 minutes).
 *
 * <p>The audit row is written via {@code shared :: audit}'s {@code AuditService} (KH-0.6b —
 * migrated off the ADR-09 direct-{@code JdbcTemplate}-insert stopgap) only when the sweep actually
 * changed rows, and carries only the count as non-sensitive metadata: no codes, no refs, no
 * disclosure material (SEC §9). The actor is always {@code SYSTEM} — this runs on a scheduler
 * thread with no {@code SecurityContext}.
 */
@Component
@ConditionalOnProperty(name = "khatm.worker.enabled", havingValue = "true")
public class ClaimCodeExpiryWorker {

  private static final Logger log = LoggerFactory.getLogger(ClaimCodeExpiryWorker.class);

  private final ClaimCodeRepository claimCodes;
  private final AuditService audit;

  public ClaimCodeExpiryWorker(ClaimCodeRepository claimCodes, AuditService audit) {
    this.claimCodes = claimCodes;
    this.audit = audit;
  }

  /**
   * One sweep tick: zero every expired, unclaimed, still-populated claim code; log the count; and
   * record an audit row only when at least one row changed.
   *
   * <p>{@code @Transactional} + {@code @Scheduled} on the same method works because the scheduler
   * invokes the Spring proxy, so the transaction wraps the bulk update + audit insert together — if
   * the audit write fails, the zeroing rolls back too (no silent partial result).
   *
   * @return the number of claim codes zeroed, so tests can assert without re-querying
   */
  @Transactional
  @Scheduled(fixedDelayString = "${khatm.worker.claim-code.expiry-sweep-ms:300000}")
  public int sweep() {
    int zeroed = claimCodes.zeroExpiredUnclaimed();
    if (zeroed > 0) {
      log.info("claim_code expiry sweep zeroed {} expired unclaimed code(s)", zeroed);
      audit.record(AuditAction.CLAIM_CODES_EXPIRED, "claim_code", null, Map.of("count", zeroed));
    } else {
      log.debug("claim_code expiry sweep: nothing to zero");
    }
    return zeroed;
  }
}
