package sy.khatm.platform.status.api;

import java.util.UUID;

/**
 * SPI for flipping a status-list bit on revoke (spec FS-1.3 D3).
 *
 * <p>One of the {@code status} module's cross-module surfaces, alongside {@link
 * StatusListAllocator} (issuance-time bit reservation) and {@link StatusListLookup} (read-only
 * resolution). The {@code credential} module calls {@link #revoke} from inside its own revoke
 * transaction — the bit flip and {@code credential.revoked} both commit atomically together (D3:
 * "no window where revoked=true but the bit is still 0"). Publishing the re-signed artifact is a
 * separate, asynchronous step triggered by the {@link StatusListRef} this method's implementation
 * publishes as a {@code StatusListChanged} event, not something the caller waits on.
 */
public interface StatusListRevoker {

  /**
   * Flip the bit at {@code idx} on the status list identified by {@code statusListId} and raise its
   * version by one, holding a row lock for the duration (spec D3/DoD #5: two concurrent revokes on
   * the same list, different credentials, never lose an update — the lock serializes them, both
   * bits end up flipped, and the version advances by exactly 2).
   *
   * <p>Un-revoke does not exist (spec §1, out of scope) — this method is one-directional; flipping
   * an already-set bit again is a harmless no-op on the bit itself, but still raises the version
   * (idempotent revoke-of-an-already-revoked-credential is guarded by the caller, not here).
   *
   * @param statusListId the status list owning the bit; must reference an existing row (the {@code
   *     credential.status_list_id} foreign key guarantees this)
   * @param idx the bit index to set, as originally allocated by {@link
   *     StatusListAllocator#allocate}
   * @return the list's {@code list_code} + new version, wrapped as a resolvable {@link
   *     StatusListRef}
   */
  StatusListRef revoke(UUID statusListId, int idx);
}
