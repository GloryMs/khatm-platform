package sy.khatm.platform.rbac.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.rbac.domain.UserTotpRecoveryCode;

/**
 * Repository for {@link UserTotpRecoveryCode} entities.
 *
 * <p>Module-private — only the {@code rbac} module's domain services may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale.
 */
@Transactional(readOnly = true)
public interface UserTotpRecoveryCodeRepository extends JpaRepository<UserTotpRecoveryCode, UUID> {

  /**
   * A user's still-usable recovery codes — {@code TotpService#consumeRecoveryCode} loads these and
   * checks the submitted code against each hash in turn (only ever up to 10 rows, so a linear scan
   * over a non-equality-comparable hash is cheap and needs no special indexing).
   */
  List<UserTotpRecoveryCode> findByUserIdAndUsedAtIsNull(UUID userId);

  /**
   * How many recovery codes remain unused — surfaced in the login response after a code is spent.
   */
  long countByUserIdAndUsedAtIsNull(UUID userId);

  /**
   * Invalidate every remaining unused code for a user (admin reset, or re-enrollment superseding a
   * prior confirmed set) — an {@code UPDATE}, never a {@code DELETE} (no {@code DELETE} grant on
   * this table, spec V7's documented default).
   */
  @Modifying
  @Transactional
  @Query(
      "UPDATE UserTotpRecoveryCode c SET c.usedAt = :now WHERE c.userId = :userId AND c.usedAt IS"
          + " NULL")
  int invalidateAllUnused(@Param("userId") UUID userId, @Param("now") Instant now);
}
