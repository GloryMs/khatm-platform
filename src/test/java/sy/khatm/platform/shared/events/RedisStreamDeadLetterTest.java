package sy.khatm.platform.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.credential.events.CredentialIssued;

/**
 * Task step 7c — a handler that always fails is retried up to {@code maxAttempts} (default 3); the
 * entry is then moved to the {@code khatm.dlq} dead-letter stream and the original stream entry is
 * ACKed (cleared from the group's pending list).
 *
 * <p>Own dedicated Postgres + Redis (not shared with the round-trip test class) so this context is
 * the sole consumer.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class RedisStreamDeadLetterTest {

  // KH-2.1 (spec FS-2.1 D3): provisions khatm_app before Flyway's first migration run —
  // V7__rls_policies.sql GRANTs to it, so it must already exist even though this test's own
  // app datasource keeps using the container's owner role (no RLS-specific assertions here).
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("db/khatm-app-role-init.sql");
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
  private static final Path KEYSTORE;

  static {
    POSTGRES.start();
    REDIS.start();
    try {
      KEYSTORE = Files.createTempFile("khatm-dlq-keys-", ".p12");
      Files.deleteIfExists(KEYSTORE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("khatm.worker.enabled", () -> "true");
    r.add("khatm.events.externalize", () -> "true");
    r.add("khatm.worker.stream.poll-interval-ms", () -> "200");
    r.add("khatm.worker.stream.group", () -> "test-dlq");
    r.add("khatm.worker.claim-code.expiry-sweep-ms", () -> "3600000");
    r.add("khatm.keys.soft.keystore-path", KEYSTORE::toString);
    r.add("khatm.keys.soft.passphrase", () -> "worker-test-passphrase");
    r.add("khatm.claims.enc-key", () -> "a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=");
    r.add("khatm.auth.totp.enc-key", () -> "a2hhdG0tdGVzdC10b3RwLWVuYy1rZXktMzJieXRlcyE=");
    // KH-0.6b: AdminBootstrap also fails startup without these outside 'local' (spec FS-0.6b D10).
    r.add("khatm.auth.bootstrap.admin-username", () -> "test-admin");
    r.add("khatm.auth.bootstrap.admin-password", () -> "test-admin-password-change-me");
    // chore/public-base-url: PublicUrlBuilder fails startup on a blank khatm.public-base-url
    // outside 'local' — this suite runs under no active profile.
    r.add("khatm.public-base-url", () -> "http://localhost:8080");
  }

  @Autowired private CredentialService credentialService;
  @Autowired private StringRedisTemplate redis;

  @Test
  void handlerFailing_pastMaxAttempts_movesEntryToDlqAndAcksOriginal() {
    // Publishing CredentialIssued sends it to the stream; this context's group consumes it, the
    // failing handler exhausts its retries, and the dispatcher dead-letters + acks.
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "WorkerDlq/v1",
                "holder-dlq-" + UUID.randomUUID(),
                1,
                60,
                Map.of("result", "NO_RECORD"),
                List.of(),
                null));

    // The DLQ receives the entry.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(redis.opsForStream().size("khatm.dlq")).isGreaterThan(0));

    // The dead-lettered entry carries the event type + its origin stream/id, proof-shaped payload.
    List<MapRecord<String, Object, Object>> dlq =
        redis.opsForStream().range("khatm.dlq", Range.unbounded(), Limit.unlimited());
    assertThat(dlq).isNotEmpty();
    Map<Object, Object> latest = dlq.get(dlq.size() - 1).getValue();
    assertThat(latest.get("type")).isEqualTo(CredentialIssued.class.getName());
    assertThat(latest.get("originStream")).isEqualTo("khatm.credential.events");

    // The original entry is ACKed — no pending entries left for this group.
    await().atMost(Duration.ofSeconds(10)).untilAsserted(this::assertNoPendingForGroup);

    assertThat(issued.ref()).isNotBlank();
  }

  private void assertNoPendingForGroup() {
    PendingMessagesSummary pending =
        redis.opsForStream().pending("khatm.credential.events", "test-dlq");
    long total = pending == null ? 0 : pending.getTotalPendingMessages();
    assertThat(total).isEqualTo(0);
  }

  /** A handler that always fails, forcing the retry-then-dead-letter path. */
  static final class AlwaysFailingHandler implements StreamEventHandler {
    @Override
    public String eventType() {
      return CredentialIssued.class.getName();
    }

    @Override
    public void handle(String payload) {
      throw new IllegalStateException("forced failure for DLQ test");
    }
  }

  @TestConfiguration
  static class FailingHandlerConfig {
    @Bean
    AlwaysFailingHandler credentialIssuedFailingHandler() {
      return new AlwaysFailingHandler();
    }
  }
}
