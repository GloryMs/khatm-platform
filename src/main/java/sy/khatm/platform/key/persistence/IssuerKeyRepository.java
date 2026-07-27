package sy.khatm.platform.key.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.key.domain.IssuerKey;

/**
 * Repository for {@link IssuerKey} entities.
 *
 * <p>Module-private — only the {@code key} module's domain services may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} makes every
 * derived-query method here safe to call standalone (no ambient transaction) — without it, such a
 * call runs via {@code SharedEntityManagerCreator}'s non-transactional path, so {@code
 * shared.TenantContextTransactionExecutionListener} never fires and RLS closed-fails to zero rows
 * regardless of the real data (see {@code RepositoryDefaultTransactionsTest}'s Javadoc for the full
 * story). Lowest priority in Spring's annotation lookup, so a method with its own more specific
 * {@code @Transactional} (below) or one called from inside a real service transaction is
 * unaffected.
 */
@Transactional(readOnly = true)
public interface IssuerKeyRepository extends JpaRepository<IssuerKey, UUID> {

  Optional<IssuerKey> findByKid(String kid);

  Optional<IssuerKey> findByTenantIdAndState(UUID tenantId, String state);

  List<IssuerKey> findByTenantIdAndStateIn(UUID tenantId, List<String> states);

  /**
   * Every key for a tenant regardless of state, newest first (spec FS-1.5.4 #4, {@code GET
   * /api/v1/admin/signing-keys}) — unlike {@link #findByTenantIdAndStateIn}, this includes {@code
   * RETIRED} keys too, since the admin endpoint is a full lifecycle view, not the publishable
   * subset JWKS serves.
   */
  List<IssuerKey> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  long countByTenantId(UUID tenantId);

  /**
   * Flip the tenant's current {@code ACTIVE} key (if any) to {@code RETIRING}.
   *
   * <p>A JPQL bulk {@code UPDATE} runs immediately against the database rather than being deferred
   * to Hibernate's flush-time ordering (which flushes pending inserts before pending updates).
   * {@link sy.khatm.platform.key.domain.KeyLifecycleService#rotate} relies on that: the old row
   * must leave the {@code ACTIVE} set <em>before</em> the new row is inserted as {@code ACTIVE}, or
   * the {@code issuer_key_one_active} partial unique index would reject the insert.
   *
   * @param tenantId the tenant whose active key should be retired
   * @param retiredAt the timestamp to record as the retired key's {@code valid_to}
   * @return the number of rows updated (0 or 1 — the partial unique index guarantees at most one
   *     {@code ACTIVE} row per tenant)
   */
  @Modifying
  @Transactional
  @Query(
      "UPDATE IssuerKey k SET k.state = 'RETIRING', k.validTo = :retiredAt "
          + "WHERE k.tenantId = :tenantId AND k.state = 'ACTIVE'")
  int retireActive(@Param("tenantId") UUID tenantId, @Param("retiredAt") Instant retiredAt);
}
