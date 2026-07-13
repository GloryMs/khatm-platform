package sy.khatm.platform.status.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import sy.khatm.platform.status.domain.StatusList;

/**
 * Repository for {@link StatusList} entities.
 *
 * <p>Module-private — only {@code StatusListAllocatorService} may use this.
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
}
