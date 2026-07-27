package sy.khatm.platform.status.api;

import java.util.UUID;

/**
 * SPI for allocating status-list bit positions.
 *
 * <p>One of the {@code status} module's cross-module surfaces, alongside {@link StatusListRevoker}
 * and {@link StatusListLookup}. The {@code credential} module depends on this interface to obtain a
 * {@code status_list_id} + {@code status_idx} pair when issuing, never on the {@code status}
 * module's internal entities.
 */
public interface StatusListAllocator {

  /**
   * Allocate the next free bit on the status list identified by {@code listCode} for the current
   * tenant, creating the list first if it does not yet exist.
   *
   * <p>This method only reserves a unique {@code (status_list_id, status_idx)} pair so that {@code
   * credential} rows have somewhere to point; signing and publishing the artifact (KH-1.3, spec
   * FS-1.3 D1/D5) and flipping bits on revoke ({@link StatusListRevoker#revoke}) are separate.
   *
   * @param listCode the status list code, e.g. {@code moj-2026}; must not be {@code null} or blank
   * @return the allocated status list id + bit index
   */
  StatusAllocation allocate(String listCode);

  /**
   * Ensure a status list named {@code listCode} exists for {@code tenantId}, creating an empty one
   * (default capacity, no bit allocated) if it does not — spec FS-2.1 D6's tenant-onboarding step.
   *
   * <p>Takes an explicit {@code tenantId} rather than resolving {@code TenantContext.current()} the
   * way {@link #allocate} does: the platform-admin tenant onboarding plane is a cross-tenant
   * control surface (an admin operator's own ambient tenant is never the tenant being onboarded),
   * unlike {@link #allocate}'s single-tenant-scoped issuance caller.
   *
   * <p>Unlike {@link #allocate}, this never consumes a bit: onboarding just needs the list to exist
   * (for the console/dashboards, before any credential is ever issued under it), not a phantom
   * allocation with no credential behind it. Idempotent — calling this on a tenant that already has
   * the list does nothing.
   *
   * @param tenantId the tenant to create the list for
   * @param listCode the status list code, e.g. {@code acme-2026}; must not be {@code null} or blank
   */
  void ensureList(UUID tenantId, String listCode);
}
