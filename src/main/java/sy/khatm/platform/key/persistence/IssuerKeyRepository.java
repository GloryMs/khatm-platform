package sy.khatm.platform.key.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sy.khatm.platform.key.domain.IssuerKey;

/**
 * Repository for {@link IssuerKey} entities.
 *
 * <p>Module-private — only the {@code key} module's domain services may use this.
 */
public interface IssuerKeyRepository extends JpaRepository<IssuerKey, UUID> {

  Optional<IssuerKey> findByKid(String kid);

  Optional<IssuerKey> findByTenantIdAndState(UUID tenantId, String state);

  List<IssuerKey> findByTenantIdAndStateIn(UUID tenantId, List<String> states);

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
  @Query(
      "UPDATE IssuerKey k SET k.state = 'RETIRING', k.validTo = :retiredAt "
          + "WHERE k.tenantId = :tenantId AND k.state = 'ACTIVE'")
  int retireActive(@Param("tenantId") UUID tenantId, @Param("retiredAt") Instant retiredAt);
}
