package sy.khatm.platform.credential.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

  /**
   * Locate a claim code by its {@code code_hash}, taking a {@code SELECT ... FOR UPDATE} row lock
   * (spec FS-1.2.1 D2/§3) — the sole mechanism that resolves a race between a redeem and a
   * concurrent {@code ClaimCodeExpiryWorker} sweep touching the same row: whichever transaction's
   * statement reaches the row first (this lock, or the sweep's bulk {@code UPDATE}) makes the other
   * wait until it commits, so neither can observe or act on a half-updated row.
   *
   * @param codeHash SHA-256 of the caller-supplied raw claim code
   * @return the locked row, or empty if no claim code has this hash
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM ClaimCode c WHERE c.codeHash = :codeHash")
  Optional<ClaimCode> findByCodeHashForUpdate(@Param("codeHash") byte[] codeHash);

  /**
   * Zero {@code disclosures_enc} on every still-live (unclaimed, not yet zeroed) claim code for one
   * credential — the "one live code per credential" half of KH-1.2.2's mint endpoint (spec FS-1.2.1
   * D2's re-issue recovery path): minting a fresh code voids whatever the previous one still was,
   * the same zeroing mechanism {@link #zeroExpiredUnclaimed()} and the redeem path already use,
   * collapsing the voided row to the same terminal, unredeemable shape either of those leaves
   * behind (an external caller attempting the old code gets the identical generic {@code
   * KH-CLM-0404}).
   *
   * @param credentialId the credential whose prior pending codes should be voided
   * @return the number of claim codes voided
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE ClaimCode c SET c.disclosuresEnc = NULL "
          + "WHERE c.credentialId = :credentialId "
          + "AND c.disclosuresEnc IS NOT NULL "
          + "AND c.claimedAt IS NULL")
  int zeroPendingForCredential(@Param("credentialId") UUID credentialId);
}
