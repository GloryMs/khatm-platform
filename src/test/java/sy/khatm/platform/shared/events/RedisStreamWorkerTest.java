package sy.khatm.platform.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.credential.events.CredentialIssued;

/**
 * Task step 7a (outbox round-trip) and 7b (idempotency). A {@code CredentialIssued} event published
 * inside {@code issue()} lands completed in the {@code event_publication} outbox, is externalized
 * to the {@code khatm.credential.events} stream, and is received by the consumer group's handler;
 * re-dispatching the same entry id applies the side effect exactly once.
 *
 * <p>Own dedicated Postgres + Redis (not shared with the DLQ test class) so this context is the
 * sole consumer and there is no cross-context contention on the broker while both worker contexts
 * stay cached.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class RedisStreamWorkerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
  private static final Path KEYSTORE;

  static {
    POSTGRES.start();
    REDIS.start();
    try {
      KEYSTORE = Files.createTempFile("khatm-roundtrip-keys-", ".p12");
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
    r.add("khatm.worker.stream.group", () -> "test-recording");
    r.add("khatm.worker.claim-code.expiry-sweep-ms", () -> "3600000");
    r.add("khatm.keys.soft.keystore-path", KEYSTORE::toString);
    r.add("khatm.keys.soft.passphrase", () -> "worker-test-passphrase");
    r.add("khatm.claims.enc-key", () -> "a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=");
    // KH-0.6b: AdminBootstrap also fails startup without these outside 'local' (spec FS-0.6b D10).
    r.add("khatm.auth.bootstrap.admin-username", () -> "test-admin");
    r.add("khatm.auth.bootstrap.admin-password", () -> "test-admin-password-change-me");
    // chore/public-base-url: PublicUrlBuilder fails startup on a blank khatm.public-base-url
    // outside 'local' — this suite runs under no active profile.
    r.add("khatm.public-base-url", () -> "http://localhost:8080");
  }

  @Autowired private CredentialService credentialService;
  @Autowired private RecordingHandler handler;
  @Autowired private StreamEventDispatcher dispatcher;
  @Autowired private JdbcTemplate jdbc;

  // ── 7a: outbox → stream → consumer group ───────────────────────────────────────────────────

  @Test
  void issue_externalizesToStreamAndConsumerGroupReceivesIt() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "WorkerRoundTrip/v1",
                "holder-roundtrip-" + UUID.randomUUID(),
                1,
                60,
                Map.of("result", "NO_RECORD"),
                List.of()));

    // The outbox captured the event — a CredentialIssued row in event_publication.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(credentialIssuedPublicationCount()).isGreaterThan(0));

    // ...externalized to the stream and received by the consumer group's handler (the handler
    // only ever sees an entry that was externalized + delivered, so this also proves the outbox
    // row was marked complete by the externalizer).
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(handler.refsReceived()).contains(issued.ref()));
  }

  private Integer credentialIssuedPublicationCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%CredentialIssued%'",
        Integer.class);
  }

  // ── 7b: idempotency — same entry id redelivered → side effect applied once ─────────────────

  @Test
  void redeliveredSameEntryId_handlerAppliedExactlyOnce() {
    String marker = "IDEM-" + UUID.randomUUID();
    // A valid-format (but far-future, non-colliding) stream id: XACK of a non-existent id is a
    // no-op (returns 0), so this exercises the dispatcher's idempotency without a real entry.
    String entryId = "9999999999999-0";

    dispatcher.dispatch(
        "khatm.credential.events", entryId, CredentialIssued.class.getName(), marker);
    dispatcher.dispatch(
        "khatm.credential.events", entryId, CredentialIssued.class.getName(), marker);

    assertThat(handler.markerCount(marker)).isEqualTo(1);
  }

  /** Records every payload this handler is handed, so tests can assert on receipt + idempotency. */
  static final class RecordingHandler implements StreamEventHandler {
    private final List<String> payloads = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String eventType() {
      return CredentialIssued.class.getName();
    }

    @Override
    public void handle(String payload) {
      payloads.add(payload);
    }

    /** The {@code ref} of every CredentialIssued received, parsed out of each payload's JSON. */
    List<String> refsReceived() {
      return payloads.stream()
          .map(
              p -> {
                try {
                  return JSON.readTree(p).path("ref").asText();
                } catch (Exception e) {
                  return "";
                }
              })
          .toList();
    }

    /** How many times {@code marker} was handed to {@link #handle}. */
    long markerCount(String marker) {
      return payloads.stream().filter(marker::equals).count();
    }
  }

  /** Registers the recording handler bean for this test's context only. */
  @TestConfiguration
  static class RecordingHandlerConfig {
    @Bean
    RecordingHandler credentialIssuedRecordingHandler() {
      return new RecordingHandler();
    }
  }
}
