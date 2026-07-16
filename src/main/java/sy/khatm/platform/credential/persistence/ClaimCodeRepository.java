package sy.khatm.platform.credential.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import sy.khatm.platform.credential.domain.ClaimCode;

/**
 * Repository for {@link ClaimCode} entities.
 *
 * <p>Module-private — only {@code CredentialService} and the module's own worker ({@code
 * ClaimCodeExpiryWorker}) may use this.
 */
public interface ClaimCodeRepository extends JpaRepository<ClaimCode, UUID> {

  /**
   * Zero {@code disclosures_enc} on every claim code whose TTL has elapsed and that was never
   * claimed — the expiry half of FS-0.2 §3.7's zeroing contract (the on-claim half belongs to the
   * claim-delivery endpoint, KH-1.2.1).
   *
   * <p>A single conditional bulk {@code UPDATE}: only rows that are expired ({@code expires_at <
   * now}), still carry encrypted disclosures ({@code disclosures_enc IS NOT NULL}), and were never
   * claimed ({@code claimed_at IS NULL}) are touched. JPQL (not native) is fine here — this is a
   * plain maintenance update, not the atomic-consume invariant and not RLS-adjacent (CONVENTIONS
   * §5). The execute-and-return-count shape means the caller can decide whether to write an audit
   * row (only when something actually changed) and log a meaningful count.
   *
   * <p>{@code flushAutomatically}/{@code clearAutomatically} so the bulk update commits its effect
   * to the DB and does not leave stale managed entities in the persistence context — the caller
   * writes the {@code audit_log} row via {@code JdbcTemplate} afterward, which bypasses the
   * persistence context anyway, so a clear is safe.
   *
   * @return the number of claim codes whose {@code disclosures_enc} was zeroed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE ClaimCode c SET c.disclosuresEnc = NULL "
          + "WHERE c.expiresAt < CURRENT_TIMESTAMP "
          + "AND c.disclosuresEnc IS NOT NULL "
          + "AND c.claimedAt IS NULL")
  int zeroExpiredUnclaimed();
}
