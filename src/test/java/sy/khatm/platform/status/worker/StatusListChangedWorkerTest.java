package sy.khatm.platform.status.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import sy.khatm.platform.status.api.StatusAllocation;
import sy.khatm.platform.status.api.StatusListAllocator;
import sy.khatm.platform.status.api.StatusListRevoker;

/**
 * Spec FS-1.3 D3 — a revoke's {@code StatusListChanged} event, delivered through the real outbox →
 * Redis Stream → consumer-group pipeline (ADR-09), reaches {@link StatusListChangedHandler} and
 * triggers a genuine artifact publish — the near-real-time half of the publish pipeline (the
 * periodic {@code StatusListPublishSweepWorker} is the safety-net half, covered in {@code
 * status.domain.StatusListPublishTest}). Sets {@code khatm.status.publish.debounce} to an hour so
 * only the event path (not an incidental sweep tick) could have produced the publish this test
 * asserts on.
 *
 * <p>Own dedicated Postgres + Redis, same rationale as {@code shared.events.RedisStreamWorkerTest}:
 * a worker-role context with real event externalization.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class StatusListChangedWorkerTest {

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
      KEYSTORE = Files.createTempFile("khatm-status-worker-keys-", ".p12");
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
    r.add("khatm.worker.stream.group", () -> "test-status-worker");
    r.add("khatm.worker.claim-code.expiry-sweep-ms", () -> "3600000");
    // Long enough that the periodic sweep can't be the thing that published within this test's
    // await window — only the event-driven handler could have done it.
    r.add("khatm.status.publish.debounce", () -> "3600000");
    r.add("khatm.keys.soft.keystore-path", KEYSTORE::toString);
    r.add("khatm.keys.soft.passphrase", () -> "status-worker-test-passphrase");
    r.add("khatm.claims.enc-key", () -> "a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=");
    r.add("khatm.auth.totp.enc-key", () -> "a2hhdG0tdGVzdC10b3RwLWVuYy1rZXktMzJieXRlcyE=");
    r.add("khatm.auth.bootstrap.admin-username", () -> "test-admin");
    r.add("khatm.auth.bootstrap.admin-password", () -> "test-admin-password-change-me");
    // chore/public-base-url: PublicUrlBuilder fails startup on a blank khatm.public-base-url
    // outside 'local' — this suite runs under no active profile.
    r.add("khatm.public-base-url", () -> "http://localhost:8080");
  }

  @Autowired private StatusListAllocator allocator;
  @Autowired private StatusListRevoker revoker;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void revoke_publishesViaEventDrivenHandler_notTheSweep() {
    StatusAllocation a = allocator.allocate("worker-test-" + UUID.randomUUID());

    revoker.revoke(a.statusListId(), a.idx());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              String artifact =
                  jdbc.queryForObject(
                      "SELECT signed_artifact FROM status_list WHERE id = ?",
                      String.class,
                      a.statusListId());
              assertThat(artifact).isNotNull();
            });
  }
}
