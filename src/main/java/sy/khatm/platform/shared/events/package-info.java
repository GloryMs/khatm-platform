/**
 * The ADR-09 async backbone infrastructure: Spring Modulith externalized events → transactional
 * outbox → Redis Streams (publish side), and the consumer-group / idempotency / dead-letter
 * machinery (consume side).
 *
 * <p><b>Publish side</b> ({@link
 * sy.khatm.platform.shared.events.RedisStreamsExternalizationConfig}): a custom {@code
 * DelegatingEventExternalizer} bean that {@code XADD}s each {@code @Externalized} event to its
 * target stream. There is no official {@code spring-modulith-events-redis} for Modulith 1.2.x, so
 * this provides the transport the official amqp/kafka completions provide, for Redis Streams.
 *
 * <p><b>Consume side</b> ({@link sy.khatm.platform.shared.events.WorkerStreamConfig}, active only
 * when {@code khatm.worker.enabled=true}): {@link
 * sy.khatm.platform.shared.events.RedisStreamConsumer} polls {@code khatm.credential.events} in the
 * {@code khatm-workers} consumer group and hands each entry to {@link
 * sy.khatm.platform.shared.events.StreamEventDispatcher}, which de-duplicates by entry id, retries
 * up to {@code khatm.worker.stream.max-attempts} (default 3), then dead-letters to {@code
 * khatm.dlq}.
 *
 * <p><b>Exposed:</b> {@link sy.khatm.platform.shared.events.StreamEventHandler} — the SPI other
 * modules implement to consume an event type. {@link
 * sy.khatm.platform.shared.events.WorkerStreamProperties} binds {@code khatm.worker.stream.*}.
 *
 * <p>This is a sub-package of {@code shared} (cross-cutting infra), <b>not</b> a separate Modulith
 * module. The {@code events} named interface lets owning modules register {@code
 * StreamEventHandler}s without a new module boundary.
 */
@org.springframework.modulith.NamedInterface("events")
package sy.khatm.platform.shared.events;
