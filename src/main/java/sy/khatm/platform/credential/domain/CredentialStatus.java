package sy.khatm.platform.credential.domain;

import java.time.Instant;

/**
 * The explicit credential lifecycle status shown on every surface (spec FS-1.6 D1) — console
 * search/detail ({@link CredentialMapper}, {@code CredentialService#toSummary}), the public {@code
 * POST /api/v1/credentials/holder-status} endpoint, and (via {@code
 * sy.khatm.platform.shared.error.VerifyReason#EXHAUSTED}) {@code /verify}.
 *
 * <p>Deliberately derived at read time from {@link Credential}'s existing columns rather than
 * stored — {@code revoked}, {@code usesRemaining}, and {@code validTo} already fully determine it,
 * so no new migration is needed (spec FS-1.6's own hard constraint). Precedence when more than one
 * condition holds: an explicit {@link #REVOKED} always wins (the administrative, intentional
 * terminal state), then {@link #EXHAUSTED} (a credential that ran out of uses before or after its
 * validity window still reads as exhausted, not expired), then {@link #EXPIRED}.
 *
 * <p>{@link #SUSPENDED} is part of spec D1's published vocabulary for forward contract stability
 * (console/wallet can code against all five values from day one) but is <b>not reachable by {@link
 * #derive}</b> in this session — nothing today puts an individual credential into a suspended
 * state; KH-2.1's tenant suspension deliberately does not affect already-issued credentials'
 * verify/consume/status-list serving. Revisit if a future session introduces a credential- or
 * schema-level suspension mechanism.
 *
 * <p>This class is module-private.
 */
enum CredentialStatus {
  ACTIVE,
  EXHAUSTED,
  REVOKED,
  SUSPENDED,
  EXPIRED;

  /**
   * Derive {@code c}'s current lifecycle status from its stored columns.
   *
   * @param c the credential to classify
   * @param now the instant to evaluate expiry against
   * @return the derived status
   */
  static CredentialStatus derive(Credential c, Instant now) {
    if (c.isRevoked()) {
      return REVOKED;
    }
    if (c.getUsesRemaining() <= 0) {
      return EXHAUSTED;
    }
    if (c.getValidTo().isBefore(now)) {
      return EXPIRED;
    }
    return ACTIVE;
  }
}
