package sy.khatm.platform.shared.events;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The Redis Streams consumer: ensures the {@code khatm-workers} consumer group exists, then polls
 * the {@code khatm.credential.events} stream on a schedule, handing each entry to the {@link
 * StreamEventDispatcher} (task step 4).
 *
 * <p>Uses {@code XREADGROUP ... >} (new, undelivered entries for this consumer) with no blocking —
 * the {@code @Scheduled} cadence is the poll loop. Each entry is ACKed inside {@link
 * StreamEventDispatcher#dispatch} (after success or dead-lettering), so the pending-entries list
 * stays empty in the steady state.
 *
 * <p>Worker-role only — instantiated by {@link WorkerStreamConfig} when {@code
 * khatm.worker.enabled=true}.
 */
class RedisStreamConsumer {

  private static final Logger log = LoggerFactory.getLogger(RedisStreamConsumer.class);

  private final StringRedisTemplate redis;
  private final WorkerStreamProperties props;
  private final StreamEventDispatcher dispatcher;
  private final String consumerName;

  RedisStreamConsumer(
      StringRedisTemplate redis, WorkerStreamProperties props, StreamEventDispatcher dispatcher) {
    this.redis = redis;
    this.props = props;
    this.dispatcher = dispatcher;
    this.consumerName = "worker-" + UUID.randomUUID();
  }

  /** Ensure the consumer group exists before the first poll (idempotent across restarts). */
  @PostConstruct
  void ensureGroup() {
    String stream = props.credentialEventsStream();
    String group = props.group();
    try {
      redis.opsForStream().createGroup(stream, ReadOffset.latest(), group);
      log.info("created consumer group {} on stream {}", group, stream);
    } catch (DataAccessException e) {
      // Either the stream key does not exist yet (no event ever published — XGROUP needs the key)
      // or the group already exists (BUSYGROUP). Seed the stream so the key exists, then retry; if
      // the retry also fails it is BUSYGROUP, which is the normal already-exists case.
      try {
        redis.opsForStream().add(stream, Map.of("_seed", "init"));
        redis.opsForStream().createGroup(stream, ReadOffset.latest(), group);
        log.info("seeded stream {} and ensured consumer group {}", stream, group);
      } catch (DataAccessException e2) {
        log.info("consumer group {} on {} already exists", group, stream);
      }
    }
  }

  /** One poll tick: read new entries for this consumer and dispatch each. */
  @Scheduled(fixedDelayString = "${khatm.worker.stream.poll-interval-ms:2000}")
  void poll() {
    List<MapRecord<String, Object, Object>> records;
    try {
      records =
          redis
              .opsForStream()
              .read(
                  Consumer.from(props.group(), consumerName),
                  StreamReadOptions.empty().count(100),
                  StreamOffset.create(props.credentialEventsStream(), ReadOffset.lastConsumed()));
    } catch (DataAccessException e) {
      log.warn("stream poll on {} failed: {}", props.credentialEventsStream(), e.toString());
      return;
    }
    if (records == null || records.isEmpty()) {
      return;
    }
    for (MapRecord<String, Object, Object> record : records) {
      try {
        dispatcher.dispatch(record);
      } catch (Exception e) {
        // dispatch swallows handler errors; this guards against unexpected infra failures so one
        // bad entry never kills the poller.
        log.error("unexpected error dispatching stream entry {}", record.getId(), e);
      }
    }
  }
}
