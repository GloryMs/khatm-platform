package sy.khatm.platform.status.api;

/**
 * SPI for allocating status-list bit positions.
 *
 * <p>This is the only cross-module surface of the {@code status} module. The {@code credential}
 * module depends on this interface to obtain a {@code status_list_id} + {@code status_idx} pair
 * when issuing, never on the {@code status} module's internal entities.
 */
public interface StatusListAllocator {

  /**
   * Allocate the next free bit on the status list identified by {@code listCode} for the current
   * tenant, creating the list first if it does not yet exist.
   *
   * <p>Publishing the signed bitstring artifact and actually flipping bits on revoke is KH-1.3;
   * this method only reserves a unique {@code (status_list_id, status_idx)} pair so that {@code
   * credential} rows have somewhere to point.
   *
   * @param listCode the status list code, e.g. {@code moj-2026}; must not be {@code null} or blank
   * @return the allocated status list id + bit index
   */
  StatusAllocation allocate(String listCode);
}
