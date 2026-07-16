package sy.khatm.platform.shared.events;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Worker-role wiring for the Redis Streams consumer infrastructure (ADR-09). Active only when
 * {@code khatm.worker.enabled=true}; the {@code api} image loads none of these beans (it publishes,
 * never consumes). Declares the consumer beans as {@code @Bean} methods rather than scanning
 * {@code @Component}s so the role split is unit-testable with an {@code ApplicationContextRunner}
 * (task step 7e).
 */
@Configuration
@ConditionalOnProperty(name = "khatm.worker.enabled", havingValue = "true")
@EnableConfigurationProperties(WorkerStreamProperties.class)
class WorkerStreamConfig {

  @Bean
  StreamEventDispatcher streamEventDispatcher(
      StringRedisTemplate redis, WorkerStreamProperties props, List<StreamEventHandler> handlers) {
    return new StreamEventDispatcher(redis, props, handlers);
  }

  @Bean
  RedisStreamConsumer redisStreamConsumer(
      StringRedisTemplate redis, WorkerStreamProperties props, StreamEventDispatcher dispatcher) {
    return new RedisStreamConsumer(redis, props, dispatcher);
  }
}
