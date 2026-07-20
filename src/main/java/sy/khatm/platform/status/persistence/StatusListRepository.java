package sy.khatm.platform.status.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sy.khatm.platform.status.domain.StatusList;

/**
 * Repository for {@link StatusList} entities.
 *
 * <p>Module-private — only {@code status.domain} services may use this.
 */
public interface StatusListRepository extends JpaRepository<StatusList, UUID> {

  Optional<StatusList> findByTenantIdAndListCode(UUID tenantId, String listCode);

  /**
   * Same lookup as {@link #findByTenantIdAndListCode}, but with a {@code SELECT ... FOR UPDATE} row
   * lock held for the rest of the transaction.
   *
   * <p>{@code StatusListAllocatorService#allocate} uses this to serialise concurrent bit
   * allocations on the same list: the lock forces a second concurrent caller to wait until the
   * first commits its {@code next_idx} increment, so no two credentials on the same status list
   * ever receive the same {@code status_idx} (mirrors the atomic-consume pattern used by {@code
   * credential.consumeOne}).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<StatusList> findWithLockByTenantIdAndListCode(UUID tenantId, String listCode);

  /**
   * Same row lock as {@link #findWithLockByTenantIdAndListCode}, but by primary key — {@code
   * StatusListRevokerService#revoke} and {@code StatusListPublisher} only ever have a {@code
   * status_list_id} (from {@code credential.status_list_id}), not a {@code (tenantId, listCode)}
   * pair, to look the row up by (spec FS-1.3 D3/DoD #5: two concurrent revokes on the same list,
   * different credentials, never lose an update — this lock is what serialises them).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM StatusList s WHERE s.id = :id")
  Optional<StatusList> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Every status list whose signed artifact has fallen behind its live {@code version}, or was
   * never published at all ({@code signedArtifact IS NULL} — a freshly allocated list that has
   * never been revoked from, spec FS-1.3 D5's catch-up condition plus the "never published"
   * bootstrap case). {@code StatusListPublishSweepWorker} republishes each; {@code
   * StatusListPublisher}'s own {@code artifactVersion < version} guard makes calling this on an
   * already-current row a no-op, so a storm of revokes between sweep ticks collapses into one
   * republish per list, not one per revoke.
   */
  @Query(
      "SELECT s.id FROM StatusList s WHERE s.signedArtifact IS NULL OR s.artifactVersion <"
          + " s.version")
  List<UUID> findStaleIds();
}
