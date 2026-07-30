package sy.khatm.platform.status.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nimbusds.jwt.SignedJWT;
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
import sy.khatm.platform.key.domain.IssuerKeySummary;
import sy.khatm.platform.key.domain.KeyLifecycleService;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.status.api.StatusAllocation;
import sy.khatm.platform.status.api.StatusListAllocator;

/**
 * Spec FS-2.3 D3 — the regression test the session brief names explicitly: after a signing-key
 * rotation, the periodic sweep re-signs every one of the rotated tenant's status lists and the
 * republished artifact carries the NEW kid, within one sweep cycle, with no direct call from {@code
 * key} into {@code status} at all — only {@link sy.khatm.platform.key.events.KeyRotated}, consumed
 * by {@link KeyRotationHandler}, which forces the list stale so {@link
 * StatusListPublishSweepWorker} does the actual resign on its own next tick.
 *
 * <p>Own dedicated Postgres + Redis, same rationale as {@code StatusListChangedWorkerTest}: a
 * worker-role context with real event externalization. Unlike that test (which isolates the
 * event-driven near-real-time handler by setting the sweep debounce very long), this test wants the
 * SWEEP itself to be the thing that resigns — spec D3's own wording — so the debounce is kept short
 * instead.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class KeyRotationWorkerTest {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("db/khatm-app-role-init.sql");
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
  private static final Path KEYSTORE;

  static {
    POSTGRES.start();
    REDIS.start();
    try {
      KEYSTORE = Files.createTempFile("khatm-key-rotation-worker-keys-", ".p12");
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
    r.add("khatm.worker.stream.group", () -> "test-key-rotation-worker");
    r.add("khatm.worker.claim-code.expiry-sweep-ms", () -> "3600000");
    // Short — spec D3's own wording is "the existing sweep re-signs within one cycle," so this
    // test wants the periodic sweep, not the near-real-time handler, to be what does the resign.
    r.add("khatm.status.publish.debounce", () -> "200");
    r.add("khatm.keys.soft.keystore-path", KEYSTORE::toString);
    r.add("khatm.keys.soft.passphrase", () -> "key-rotation-worker-test-passphrase");
    r.add("khatm.claims.enc-key", () -> "a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=");
    r.add("khatm.auth.totp.enc-key", () -> "a2hhdG0tdGVzdC10b3RwLWVuYy1rZXktMzJieXRlcyE=");
    r.add("khatm.auth.bootstrap.admin-username", () -> "test-admin");
    r.add("khatm.auth.bootstrap.admin-password", () -> "test-admin-password-change-me");
    r.add("khatm.public-base-url", () -> "http://localhost:8080");
  }

  @Autowired private StatusListAllocator allocator;
  @Autowired private KeyLifecycleService lifecycle;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void rotate_forcesTenantsStatusLists_theNextSweepResignsWithTheNewKid() throws Exception {
    StatusAllocation allocation =
        allocator.allocate("key-rotation-worker-test-" + UUID.randomUUID());

    // First, let the sweep publish the initial artifact under whatever key is ACTIVE today.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(artifactFor(allocation.statusListId())).isNotNull());
    String oldKid = kidOf(artifactFor(allocation.statusListId()));

    IssuerKeySummary rotated =
        lifecycle.rotate(TenantContext.current(), TenantContext.currentSlug());
    assertThat(rotated.kid()).isNotEqualTo(oldKid);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              String artifact = artifactFor(allocation.statusListId());
              assertThat(artifact).isNotNull();
              assertThat(kidOf(artifact)).isEqualTo(rotated.kid());
            });
  }

  private String artifactFor(UUID statusListId) {
    return jdbc.queryForObject(
        "SELECT signed_artifact FROM status_list WHERE id = ?", String.class, statusListId);
  }

  private static String kidOf(String compactJws) throws Exception {
    return SignedJWT.parse(compactJws).getHeader().getKeyID();
  }
}
