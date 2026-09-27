package sy.khatm.platform.credential.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.persistence.ClaimCodeRepository;
import sy.khatm.platform.credential.persistence.CredentialRepository;

/**
 * Works out what an idempotent issuance replay can still deliver for one credential, reissuing its
 * pending claim code in place when it can (KH-2.8.2, spec FS-2.7a D7 / veto V2).
 *
 * <p><b>Why in place:</b> the platform stores only a claim code's hash, so the original code can
 * never be sent again. Instead the credential's one {@code claim_code} row gets a new code, a new
 * {@code code_hash}, and a fresh {@code expires_at}; {@code disclosures_enc} is left untouched. The
 * old code dies with its hash — it never reached anyone, so nothing leaks.
 *
 * <p><b>Race safety:</b> the row is read with {@code SELECT ... FOR UPDATE}, the same row lock
 * {@link ClaimRedemptionService#redeem} takes, and the {@code ClaimCodeExpiryWorker}'s bulk {@code
 * UPDATE} waits on it and re-checks its {@code WHERE} against the extended {@code expires_at}. So a
 * replay either reissues a row that nobody else can redeem or zero at the same moment, or it finds
 * the row already claimed / already zeroed and reports that instead. It never hands out a code
 * whose disclosures are gone.
 *
 * <p>Module-private.
 */
@Service
public class ClaimCodeReissuer {

  private static final Duration CLAIM_CODE_TTL = Duration.ofMinutes(15);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final ClaimCodeRepository claimCodes;
  private final CredentialRepository credentials;
  private final Clock clock;

  public ClaimCodeReissuer(
      ClaimCodeRepository claimCodes, CredentialRepository credentials, Clock clock) {
    this.claimCodes = claimCodes;
    this.credentials = credentials;
    this.clock = clock;
  }

  /**
   * Decide the replay delivery for {@code credentialId}.
   *
   * <p>Runs in the caller's transaction when there is one (a single {@code /issue} replay), or in
   * its own (each row of a {@code /bulk} replay).
   *
   * @param credentialId the credential the original request issued
   * @param claimCodeMode the original request asked for a claim code ({@code mintClaimCode} /
   *     {@code mintClaimCodes}); {@code false} means direct {@code sdJwt} delivery, which can never
   *     be replayed (it is not stored — P1)
   * @return claimed, a reissued code, or delivery lost
   */
  @Transactional
  public ReplayDelivery replay(UUID credentialId, boolean claimCodeMode) {
    if (claimCodes.existsByCredentialIdAndClaimedAtIsNotNull(credentialId)) {
      return ReplayDelivery.claimedAlready();
    }
    if (!claimCodeMode) {
      return ReplayDelivery.lost();
    }
    Optional<ClaimCode> locked =
        claimCodes.findFirstByCredentialIdOrderByCreatedAtDesc(credentialId);
    if (locked.isEmpty()) {
      return ReplayDelivery.lost();
    }
    ClaimCode row = locked.get();
    // Re-checked under the lock: a redeem may have committed between the exists() above and here.
    if (row.getClaimedAt() != null) {
      return ReplayDelivery.claimedAlready();
    }
    Instant now = clock.instant();
    boolean stillClaimable =
        credentials
            .findById(credentialId)
            .map(c -> !c.isRevoked() && c.getValidTo().isAfter(now))
            .orElse(false);
    if (row.getDisclosuresEnc() == null || !stillClaimable) {
      return ReplayDelivery.lost();
    }

    byte[] codeBytes = new byte[16];
    SECURE_RANDOM.nextBytes(codeBytes);
    String code = HexFormat.of().formatHex(codeBytes);
    // Microsecond precision: what timestamptz stores, so the response equals the row.
    Instant expiresAt = now.plus(CLAIM_CODE_TTL).truncatedTo(ChronoUnit.MICROS);
    row.setCodeHash(sha256(code));
    row.setExpiresAt(expiresAt);
    claimCodes.save(row);
    return ReplayDelivery.reissued(code, expiresAt);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm", e);
    }
  }
}
