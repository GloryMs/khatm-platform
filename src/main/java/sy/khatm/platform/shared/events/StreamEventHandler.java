package sy.khatm.platform.shared.events;

/**
 * A handler for one event type on a Redis Stream, registered as a Spring bean. The {@code
 * RedisStreamConsumer} dispatches each stream entry to the handler whose {@link #eventType()}
 * matches the entry's {@code type} field (the fully-qualified event class name the externalizer
 * wrote).
 *
 * <p>Handlers must be <b>idempotent</b>: the at-least-once stream + outbox model means the same
 * logical event can be delivered more than once (redelivery of an unacked entry, or a duplicate
 * externalization across instances). The {@code StreamEventDispatcher} additionally de-duplicates
 * by stream entry id, but a handler that mutates state should still tolerate a second invocation
 * for the same payload (CONVENTIONS §7 — "handlers idempotent, keyed on event id").
 *
 * <p>{@code handle} receives the event's JSON payload (proof-shaped: refs and timestamps only,
 * never claim values/disclosures/salts — SEC §9). A handler deserializes it according to its own
 * event type. Throwing triggers the dispatcher's retry-then-dead-letter path.
 *
 * @author khatm-platform
 */
public interface StreamEventHandler {

  /**
   * The event type this handler accepts — the fully-qualified class name of the externalized event
   * (e.g. {@code sy.khatm.platform.credential.events.CredentialIssued}).
   */
  String eventType();

  /**
   * Process one event payload.
   *
   * @param payload the event serialized as JSON (proof-shaped — no disclosures/PII)
   * @throws Exception if processing failed; the dispatcher retries, then dead-letters
   */
  void handle(String payload) throws Exception;
}
