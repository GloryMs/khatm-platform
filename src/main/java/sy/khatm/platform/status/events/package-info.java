/**
 * Domain events published by the status module and externalized to Redis Streams via Spring
 * Modulith's transactional outbox (ADR-09).
 *
 * <p><b>Published:</b> {@link sy.khatm.platform.status.events.StatusListChanged} — fired inside
 * {@code StatusListRevokerService#revoke}'s transaction, routed to the existing {@code
 * khatm.credential.events} stream (see the event's own Javadoc for why no new stream was added).
 *
 * <p>Every event payload here is proof-shaped: ids, versions, and timestamps only, never the
 * bitstring or a signed artifact (SEC §9 — streams and the DLQ obey the same no-disclosure rule as
 * logs).
 */
package sy.khatm.platform.status.events;
