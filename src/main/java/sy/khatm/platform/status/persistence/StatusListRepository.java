package sy.khatm.platform.status.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.status.domain.StatusList;

/**
 * Repository for {@link StatusList} entities.
 *
 * <p>Module-private — only {@code status.domain} services may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale. The pessimistic
 * locks below still need a real, longer-lived write transaction to be meaningful (the lock is only
 * held for the enclosing transaction's duration) — every real caller already wraps these in its own
 * {@code @Transactional} service method, so this type-level default only ever applies when one of
 * these is (incorrectly) called bare, which would be a bug regardless of this annotation.
 */
@Transactional(readOnly = true)
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
   *
   * <p>Carries {@code tenantId} alongside {@code id} (not a bare {@code List<UUID>}) because the
   * sweep is cross-tenant by construction (KH-2.1, spec FS-2.1 D5) — it runs under {@code
   * SystemAccessExecutor} to see every tenant's stale lists in one query, but signing each artifact
   * still needs {@code shared.TenantContext} set to that <em>specific</em> list's own tenant (the
   * key resolution {@code key.domain.KeySignerImpl} performs reads only the ambient {@code
   * TenantContext}, never a parameter) — without this, every list in the sweep would be signed with
   * whichever tenant's key happens to be ambient for the worker thread, not its own.
   */
  @Query(
      "SELECT new sy.khatm.platform.status.persistence.StaleStatusListRef(s.id, s.tenantId) FROM"
          + " StatusList s WHERE s.signedArtifact IS NULL OR s.artifactVersion < s.version")
  List<StaleStatusListRef> findStaleRefs();

  /**
   * Bump every one of a tenant's status lists' {@code version} by one, forcing each stale (spec
   * FS-2.3 D3) — the runtime equivalent of what {@code V9__resign_status_lists.sql} did once via a
   * one-off data migration, done here at signing-key rotation time instead. {@code
   * status.worker.KeyRotationHandler} calls this in reaction to {@code key.events.KeyRotated}; the
   * already-running {@link sy.khatm.platform.status.worker.StatusListPublishSweepWorker#sweep()}
   * then republishes each with whatever key is now {@code ACTIVE} for that tenant within one sweep
   * cycle. An immediate bulk statement, same rationale as {@code
   * key.persistence.IssuerKeyRepository#retireActive}.
   *
   * @param tenantId the tenant whose lists should be forced stale
   * @return the number of rows updated
   */
  @Modifying
  @Transactional
  @Query("UPDATE StatusList s SET s.version = s.version + 1 WHERE s.tenantId = :tenantId")
  int bumpVersionForTenant(@Param("tenantId") UUID tenantId);
}
