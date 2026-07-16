package sy.khatm.platform.shared.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker-side Redis Streams consumer configuration, bound from {@code khatm.worker.stream.*} in
 * {@code application.yml} (spec ADR-09, task step 4).
 *
 * <p>Consumer-role only — these only matter when {@code khatm.worker.enabled=true}.
 *
 * @param pollIntervalMs how often the consumer polls for new stream entries
 * @param maxAttempts handler failures before an entry is moved to the dead-letter stream (task step
 *     4; default 3)
 * @param group the Redis Streams consumer group name ({@code khatm-workers})
 * @param credentialEventsStream the stream externalized {@code CredentialIssued} events land on
 * @param dlqStream the dead-letter stream entries are moved to after {@code maxAttempts} failures
 */
@ConfigurationProperties(prefix = "khatm.worker.stream")
public record WorkerStreamProperties(
    int pollIntervalMs,
    int maxAttempts,
    String group,
    String credentialEventsStream,
    String dlqStream) {}
