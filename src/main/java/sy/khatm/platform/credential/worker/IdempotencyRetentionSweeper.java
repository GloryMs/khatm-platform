package sy.khatm.platform.credential.worker;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sy.khatm.platform.credential.persistence.IssuanceIdempotencyRepository;
import sy.khatm.platform.shared.SystemAccessExecutor;

/**
 * Deletes {@code issuance_idempotency} rows whose retention elapsed (KH-2.8.2, spec FS-2.7a D6,
 * veto V5): a key is remembered for {@code khatm.issuance.idempotency.retention} (default 30 days)
 * and forgotten after — a retry that late issues anew.
 *
 * <p>Worker-role only ({@code khatm.worker.enabled=true}, ADR-09); hourly by default ({@code
 * khatm.worker.issuance-idempotency.retention-sweep-ms}). Cross-tenant, so it runs under {@link
 * SystemAccessExecutor}. No audit row: this is housekeeping of proof records, not a business event.
 * Time comes from the injected {@link Clock} so tests move time instead of sleeping.
 */
@Component
@ConditionalOnProperty(name = "khatm.worker.enabled", havingValue = "true")
public class IdempotencyRetentionSweeper {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyRetentionSweeper.class);

  private final IssuanceIdempotencyRepository rows;
  private final SystemAccessExecutor systemAccess;
  private final Clock clock;

  public IdempotencyRetentionSweeper(
      IssuanceIdempotencyRepository rows, SystemAccessExecutor systemAccess, Clock clock) {
    this.rows = rows;
    this.systemAccess = systemAccess;
    this.clock = clock;
  }

  /**
   * One sweep pass.
   *
   * @return how many expired rows were deleted
   */
  @Scheduled(fixedDelayString = "${khatm.worker.issuance-idempotency.retention-sweep-ms:3600000}")
  public int sweep() {
    int deleted = systemAccess.runAsSystem(() -> rows.deleteExpired(clock.instant()));
    if (deleted > 0) {
      log.info("issuance idempotency retention sweep deleted {} expired key(s)", deleted);
    }
    return deleted;
  }
}
