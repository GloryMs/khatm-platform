package sy.khatm.platform.shared.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.modulith.events.support.DelegatingEventExternalizer;

/**
 * The publish side of the ADR-09 async backbone: bridges Spring Modulith's externalized events to
 * Redis Streams.
 *
 * <p>There is <b>no official {@code spring-modulith-events-redis} completion for Modulith 1.2.x</b>
 * (only amqp / kafka / jms / aws-sqs / aws-sns ship), so this provides the same shape those
 * completions do — a {@link DelegatingEventExternalizer} bean whose delegate performs the {@code
 * XADD}. The transactional outbox ({@code event_publication}, via {@code
 * spring-modulith-starter-jdbc}) records each event in the publishing transaction; this
 * externalizer fires <em>after commit</em>, and the outbox row is marked complete exactly when the
 * delegate's future completes successfully (at-least-once: an exceptionally-completed future leaves
 * the row incomplete for replay).
 *
 * <p>The delegate receives the <b>live event object</b> plus a {@link RoutingTarget} whose {@code
 * getTarget()} is the stream key (set by each event's {@code @Externalized("...")} annotation). It
 * serializes the payload to JSON and writes a stream entry carrying the event type + payload, then
 * returns a completed future (per the 1.2 caveat: a synchronous {@code XADD} wrapped in {@code
 * completedFuture} makes the outcome deterministic; a future that never completes would strand the
 * row).
 *
 * <p>Gated by {@code khatm.events.externalize} (default {@code true} via {@code matchIfMissing}).
 * The {@code test} profile sets it {@code false} so the existing Redis-less shared-context suite
 * never attempts an {@code XADD}; the worker-stream integration tests override it back to {@code
 * true} with their own Redis Testcontainer.
 */
@Configuration
@ConditionalOnProperty(
    name = "khatm.events.externalize",
    havingValue = "true",
    matchIfMissing = true)
class RedisStreamsExternalizationConfig {

  /**
   * Select every {@link org.springframework.modulith.events.Externalized} event type for
   * externalization, routing each to the target named by its annotation. Explicit (rather than
   * relying on the auto-config base-package default) so events in any sub-package under the
   * application root are picked up unambiguously.
   */
  @Bean
  EventExternalizationConfiguration eventExternalizationConfiguration() {
    return EventExternalizationConfiguration.externalizing()
        .select(EventExternalizationConfiguration.annotatedAsExternalized())
        .build();
  }

  /**
   * The Redis Streams transport — a {@link DelegatingEventExternalizer} wrapping a delegate that
   * {@code XADD}s each externalized event to its target stream.
   *
   * @param config the externalization selection/routing config
   * @param redis the shared {@link StringRedisTemplate} (already in the stack)
   * @param objectMapper JSON serializer for the event payload
   */
  @Bean
  DelegatingEventExternalizer redisStreamsExternalizer(
      EventExternalizationConfiguration config,
      StringRedisTemplate redis,
      ObjectMapper objectMapper) {
    BiFunction<RoutingTarget, Object, CompletableFuture<?>> delegate =
        (target, payload) -> {
          String stream = target.getTarget();
          try {
            String json = objectMapper.writeValueAsString(payload);
            var record =
                StreamRecords.string(Map.of("type", payload.getClass().getName(), "payload", json))
                    .withStreamKey(stream);
            return CompletableFuture.completedFuture(redis.opsForStream().add(record));
          } catch (Exception e) {
            // A failed future leaves the outbox row incomplete so it is replayed on restart.
            return CompletableFuture.failedFuture(e);
          }
        };
    return new DelegatingEventExternalizer(config, delegate);
  }
}
