package sy.khatm.platform.credential.domain;

import java.time.Instant;

/**
 * What an idempotent replay can still hand over for one credential (KH-2.8.2, spec FS-2.7a D7).
 *
 * @param claimCode a freshly reissued one-time claim code, or {@code null}
 * @param claimCodeExpiresAt when {@code claimCode} stops working; {@code null} with it
 * @param claimed the holder's wallet already claimed the credential
 * @param claimCodeReissued {@code claimCode} replaced the previous, never-delivered code
 * @param deliveryLost nothing deliverable is left: a direct-mode {@code sdJwt} is never stored
 *     (P1), or the pending code's disclosures were already zeroed (expired and swept, voided, or
 *     the credential is revoked or past validity). The connector decides (revoke and reissue).
 */
public record ReplayDelivery(
    String claimCode,
    Instant claimCodeExpiresAt,
    boolean claimed,
    boolean claimCodeReissued,
    boolean deliveryLost) {

  static ReplayDelivery claimedAlready() {
    return new ReplayDelivery(null, null, true, false, false);
  }

  static ReplayDelivery lost() {
    return new ReplayDelivery(null, null, false, false, true);
  }

  static ReplayDelivery reissued(String code, Instant expiresAt) {
    return new ReplayDelivery(code, expiresAt, false, true, false);
  }
}
