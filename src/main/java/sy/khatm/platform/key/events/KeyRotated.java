package sy.khatm.platform.key.events;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * Published the moment {@code key.domain.KeyLifecycleService#rotate} commits — inside the same
 * transaction — then handed to Spring Modulith's transactional outbox and externalized to Redis
 * Streams (ADR-09, spec FS-2.3 D3).
 *
 * <p>Consumed by {@code status.worker}'s rotation handler, which bumps every one of {@code
 * tenantId}'s {@code status_list} rows' {@code version} — forcing them stale so the already-running
 * {@code status.worker.StatusListPublishSweepWorker} re-signs each with the tenant's new {@code
 * ACTIVE} key within one sweep cycle (spec D3's "runtime V9" mechanism: the same effect {@code
 * V9__resign_status_lists.sql} achieved once via migration, done here at rotation time instead of
 * via a one-off data migration).
 *
 * <p><b>Deliberately routed to the existing {@code khatm.credential.events} stream</b>, the same
 * reuse rationale {@code status.events.StatusListChanged} documents for itself — {@code
 * shared.events.RedisStreamConsumer} polls exactly one configured stream, dispatching by event
 * class name; a second stream would mean a second poller/consumer-group/dead-letter wiring for no
 * benefit this feature needs.
 *
 * <p><b>P1 / SEC §9:</b> the payload is proof-shaped — {@code tenantId} + both {@code kid}s +
 * timestamp only. It never carries key material of any kind.
 *
 * <p>This event class lives in {@code key}'s own {@code events} sub-package specifically so {@code
 * status} (which already depends on {@code key :: api} for {@code KeySigner}) can consume it
 * without {@code key} ever needing to depend back on {@code status} — the direction of the
 * dependency stays exactly what it already was; only a new event type crosses it. As of KH-2.3b,
 * {@code tenant} (which already depends on {@code key :: api} for {@code TenantKeyProvisioner}/
 * {@code JwksLookup}) consumes it too, for the same reason — see {@code provider} below.
 *
 * <p><b>{@code provider} (KH-2.3b, spec FS-2.3 D5/D6, veto V3):</b> the new {@code ACTIVE} key's
 * provider ({@code SOFT}/{@code VAULT}). {@code tenant.domain.TenantKeyProviderSyncHandler}
 * consumes this to keep {@code tenant.key_provider} — the tenant-level "current provider" column —
 * in sync, without {@code key} ever writing to the {@code tenant} table directly (would need a
 * {@code key → tenant} dependency, a cycle since {@code tenant} already depends on {@code key ::
 * api}).
 *
 * @param tenantId the tenant whose key rotated
 * @param oldKid the key that moved {@code ACTIVE} → {@code RETIRING}
 * @param newKid the new {@code ACTIVE} key
 * @param provider the new {@code ACTIVE} key's provider
 * @param occurredAt when the rotation happened (UTC)
 */
@Externalized("khatm.credential.events")
public record KeyRotated(
    UUID tenantId, String oldKid, String newKid, String provider, Instant occurredAt) {}
