package sy.khatm.platform.status.events;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * Published the moment a status list's bit is flipped — inside {@code
 * StatusListRevokerService#revoke}'s transaction — then handed to Spring Modulith's transactional
 * outbox and externalized to Redis Streams (ADR-09). Worker-role consumers ({@code
 * StatusListChangedHandler}) read it from the {@code khatm-workers} consumer group and attempt an
 * immediate republish of the list's signed artifact (spec FS-1.3 D3).
 *
 * <p><b>Deliberately routed to the existing {@code khatm.credential.events} stream, not a new
 * one</b>: {@code shared.events.RedisStreamConsumer} polls exactly one configured stream today
 * ({@code khatm.worker.stream.credential-events-stream}), dispatching by event class name to
 * whichever {@code StreamEventHandler} bean matches (see {@code StreamEventDispatcher}).
 * Introducing a second stream would mean a second poller/consumer-group/dead-letter wiring for no
 * benefit this MVP needs — reusing the one that already exists is "the same ADR-09 shape" the spec
 * calls for, just without inventing new plumbing to get there. A future session that genuinely
 * needs per-stream isolation (e.g. different retention or throughput characteristics) can split it
 * out then.
 *
 * <p><b>P1 / SEC §9:</b> the payload is deliberately proof-shaped — {@code statusListId} + {@code
 * version} + timestamp only. It never carries the bitstring or the signed artifact itself.
 *
 * @param statusListId the status list whose bit changed
 * @param version the list's new version after the flip
 * @param occurredAt when the flip happened (UTC)
 */
@Externalized("khatm.credential.events")
public record StatusListChanged(UUID statusListId, long version, Instant occurredAt) {}
