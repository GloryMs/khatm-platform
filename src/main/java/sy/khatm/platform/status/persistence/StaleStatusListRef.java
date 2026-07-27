package sy.khatm.platform.status.persistence;

import java.util.UUID;

/**
 * A stale status list's id and owning tenant (KH-2.1, spec FS-2.1 D5) — {@link
 * StatusListRepository#findStaleRefs()}'s projection, carrying just enough for {@code
 * status.worker.StatusListPublishSweepWorker} to set {@code shared.TenantContext} to the correct
 * tenant before signing each list's artifact.
 *
 * @param id the status list's id
 * @param tenantId the tenant that owns this list
 */
public record StaleStatusListRef(UUID id, UUID tenantId) {}
