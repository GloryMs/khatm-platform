package sy.khatm.platform.shared.events;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The consume side of one stream entry: idempotency check, handler dispatch with synchronous retry,
 * explicit ACK after success, and dead-letter routing after {@code maxAttempts} failures (task step
 * 4).
 *
 * <p><b>Idempotency</b> is keyed on the Redis stream entry id (the "event id" at the stream level —
 * CONVENTIONS §7). A {@code khatm:processed:{stream}:{entryId}} key with a TTL records every entry
 * that has been fully handled (success OR dead-lettered); a redelivery of that same entry id is
 * ACKed and skipped, so side effects apply exactly once even though delivery is at-least-once.
 *
 * <p><b>Retry</b> is synchronous within a single delivery: the handler is retried up to {@code
 * maxAttempts} times immediately. If every attempt fails, the entry is copied to the dead-letter
 * stream ({@code khatm.dlq} by default) with its type, payload, origin stream/id, and the last
 * error, and the original entry is ACKed so it is not retried forever. (Cross-instance pending
 * reclaim via {@code XAUTOCLAIM} for crash-recovery is a documented future hardening; the
 * synchronous-retry + DLQ + idempotency here covers the task's stated at-least-once + DLQ
 * semantics.)
 *
 * <p>Module-private support class; the owning module is {@code shared}, and {@code
 * StreamEventHandler} implementations (the only cross-module touchpoint) are discovered as beans.
 */
class StreamEventDispatcher {

  private static final Logger log = LoggerFactory.getLogger(StreamEventDispatcher.class);
  private static final Duration PROCESSED_TTL = Duration.ofHours(24);

  private final StringRedisTemplate redis;
  private final WorkerStreamProperties props;
  private final Map<String, StreamEventHandler> handlersByType;

  StreamEventDispatcher(
      StringRedisTemplate redis, WorkerStreamProperties props, List<StreamEventHandler> handlers) {
    this.redis = redis;
    this.props = props;
    this.handlersByType = new HashMap<>();
    for (StreamEventHandler handler : handlers) {
      this.handlersByType.put(handler.eventType(), handler);
    }
  }

  /**
   * Dispatch a stream {@link MapRecord} read by the consumer. Field values are {@code Object} on
   * the read generic (the actual runtime values are Strings under {@code StringRedisTemplate}); we
   * normalize to {@code String} here.
   */
  void dispatch(MapRecord<String, Object, Object> record) {
    Object typeVal = record.getValue().get("type");
    Object payloadVal = record.getValue().get("payload");
    String type = typeVal == null ? "" : typeVal.toString();
    String payload = payloadVal == null ? "" : payloadVal.toString();
    dispatch(record.getStream(), record.getId().getValue(), type, payload);
  }

  /**
   * Dispatch one entry by its raw fields. Exposed so idempotency can be tested deterministically by
   * re-dispatching the same {@code entryId}.
   */
  void dispatch(String stream, String entryId, String type, String payload) {
    String processedKey = "khatm:processed:" + stream + ":" + entryId;
    if (Boolean.TRUE.equals(redis.hasKey(processedKey))) {
      log.debug("stream entry {} type={} already processed — acking and skipping", entryId, type);
      acknowledge(stream, entryId);
      return;
    }

    StreamEventHandler handler = handlersByType.get(type);
    if (handler == null) {
      // No handler for this event type yet (e.g. an event externalized ahead of its consumer).
      // Mark processed + ACK so the entry does not block the group; a future consumer can be added.
      log.debug("no handler for event type {} (entry {}) — acking", type, entryId);
      markProcessed(processedKey);
      acknowledge(stream, entryId);
      return;
    }

    Exception lastError = null;
    for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
      try {
        handler.handle(payload);
        lastError = null;
        break;
      } catch (Exception e) {
        lastError = e;
        log.warn(
            "handler for type={} failed attempt {}/{} entry={}: {}",
            type,
            attempt,
            props.maxAttempts(),
            entryId,
            e.toString());
      }
    }

    if (lastError != null) {
      deadLetter(stream, entryId, type, payload, lastError);
    }
    markProcessed(processedKey);
    acknowledge(stream, entryId);
  }

  private void markProcessed(String key) {
    redis.opsForValue().set(key, "1", PROCESSED_TTL);
  }

  private void acknowledge(String stream, String entryId) {
    redis.opsForStream().acknowledge(stream, props.group(), entryId);
  }

  /**
   * Copy the failed entry to the dead-letter stream. The payload is proof-shaped by contract
   * (CredentialIssued carries refs + timestamps only), so the DLQ obeys the same no-disclosure rule
   * as logs and streams (SEC §9).
   */
  private void deadLetter(
      String originStream, String originId, String type, String payload, Exception error) {
    Map<String, String> dlq = new LinkedHashMap<>();
    dlq.put("type", type);
    dlq.put("payload", payload);
    dlq.put("originStream", originStream);
    dlq.put("originId", originId);
    dlq.put("error", error.toString());
    redis.opsForStream().add(props.dlqStream(), dlq);
    log.error(
        "moved failed stream entry (stream={} id={} type={}) to DLQ {}",
        originStream,
        originId,
        type,
        props.dlqStream());
  }
}
