package sy.khatm.platform.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Task step 7e — the {@code worker} runtime role is selected by config, not a new module: with
 * {@code khatm.worker.enabled=true} the Redis Streams consumer beans load; with it {@code false}
 * (the {@code api} role, the default) they do not. Uses an {@link ApplicationContextRunner} so the
 * conditional wiring is asserted without a full Spring Boot context or any containers.
 *
 * <p>The {@link StringRedisTemplate} is a deep-stubs Mockito mock so {@code
 * RedisStreamConsumer.ensureGroup()} (a {@code @PostConstruct} that calls {@code
 * redis.opsForStream().createGroup(...)}) completes without hitting a real broker while we only
 * assert bean presence.
 */
class WorkerRoleGuardTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(WorkerStreamConfig.class)
          .withBean(
              StringRedisTemplate.class, () -> mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS))
          .withPropertyValues(
              "khatm.worker.stream.poll-interval-ms=2000",
              "khatm.worker.stream.max-attempts=3",
              "khatm.worker.stream.group=khatm-workers",
              "khatm.worker.stream.credential-events-stream=khatm.credential.events",
              "khatm.worker.stream.dlq-stream=khatm.dlq");

  @Test
  void workerEnabled_loadsConsumerBeans() {
    runner
        .withPropertyValues("khatm.worker.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(RedisStreamConsumer.class);
              assertThat(context).hasSingleBean(StreamEventDispatcher.class);
            });
  }

  @Test
  void workerDisabled_apiRole_loadsNoConsumerBeans() {
    runner
        .withPropertyValues("khatm.worker.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(RedisStreamConsumer.class);
              assertThat(context).doesNotHaveBean(StreamEventDispatcher.class);
            });
  }
}
