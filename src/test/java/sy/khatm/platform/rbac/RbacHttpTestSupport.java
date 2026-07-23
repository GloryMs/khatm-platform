package sy.khatm.platform.rbac;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for KH-0.6b's HTTP-level auth tests: a real embedded servlet container ({@code
 * WebEnvironment.RANDOM_PORT}) plus a real Redis container — unlike {@code
 * shared.web.ErrorEnvelopeTestSupport} (which has no Redis at all, fine for its API-key-only tests)
 * or {@code support.IntegrationTestSupport} (no HTTP, no Redis), this suite genuinely needs both:
 * {@code AuthService}'s Redis-TTL lockout counter (spec FS-0.6b D6) is not best-effort like {@code
 * CredentialService}'s idempotency cache, and Spring Session needs a real broker to persist a
 * session across the two separate HTTP calls a login → me → logout cycle requires.
 *
 * <p><b>Deliberately not {@code @Testcontainers}/{@code @Container}:</b> those JUnit5 annotations
 * bind a container's {@code start()}/{@code stop()} lifecycle to the <em>owning test class's</em>
 * {@code beforeAll}/{@code afterAll} — including for a {@code static} field merely
 * <em>inherited</em> from this abstract base. Since every concrete subclass here shares the exact
 * same cached Spring context (identical {@code @DynamicPropertySource} values), the FIRST subclass
 * to finish would stop the containers out from under every subsequent one, which is exactly what
 * happened empirically (later test classes failed with HikariCP's pool unable to open any
 * connection at all). {@code IntegrationTestSupport} already established the fix for this same
 * class of problem: start the containers once in a manual {@code static} initializer and never
 * explicitly stop them — Testcontainers' Ryuk reaper cleans up at JVM exit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class RbacHttpTestSupport {

  protected static final PostgreSQLContainer<?> POSTGRES;
  protected static final GenericContainer<?> REDIS;
  private static final Path KEYSTORE_PATH;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
    REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    REDIS.start();
    try {
      KEYSTORE_PATH = Files.createTempFile("khatm-rbac-http-test-keys-", ".p12");
      Files.deleteIfExists(KEYSTORE_PATH);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static final String BOOTSTRAP_ADMIN_USERNAME = "rbac-test-admin";
  static final String BOOTSTRAP_ADMIN_PASSWORD = "rbac-test-admin-password-change-me";

  @LocalServerPort private int port;

  /**
   * A {@link TestRestTemplate} backed by {@link JdkClientHttpRequestFactory} (the modern {@code
   * java.net.http.HttpClient}), not the JDK's legacy {@code HttpURLConnection}-based default. The
   * legacy client cannot handle a {@code 401} response to a POST it streamed with a known {@code
   * Content-Length}: it throws {@code HttpRetryException: cannot retry due to server
   * authentication, in streaming mode} internally, surfacing as an opaque {@code
   * ResourceAccessException} with no hint it was ever a {@code 401} — a well-known limitation of
   * that specific client, unrelated to anything this module does, and one that turned out to
   * survive even explicitly disabling {@code SimpleClientHttpRequestFactory}'s output-streaming
   * mode (confirmed empirically — the legacy client's {@code Authenticator}-retry codepath still
   * triggered regardless). {@code java.net.http.HttpClient} has no such codepath at all. Every DoD
   * test here deliberately provokes {@code 401}s on POST endpoints ({@code /issue}, {@code
   * /consume}, {@code /login}), so this matters for this suite specifically.
   */
  protected TestRestTemplate rest;

  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void initJdkHttpClientRestTemplate() {
    Supplier<ClientHttpRequestFactory> requestFactorySupplier = JdkClientHttpRequestFactory::new;
    rest =
        new TestRestTemplate(
            new RestTemplateBuilder()
                .requestFactory(requestFactorySupplier)
                .rootUri("http://localhost:" + port));
  }

  /**
   * Every subclass here shares one cached Spring context and one {@link TestRestTemplate}, so every
   * subclass's HTTP calls originate from the same loopback address — unlike {@code
   * khatm:auth:fail:*} (scoped per-username, already isolated by each login test's own randomized
   * username), the claim-redeem throttle counter (spec FS-1.2.1 D6) is scoped per-<em>IP</em> with
   * no such natural per-test axis. Clearing it before every test method keeps one class's redeem
   * calls (e.g. {@code PublicEndpointsNoCredentialsTest}) from silently eating into another's
   * throttle budget (e.g. {@code ClaimRedeemThrottleHttpTest}) regardless of JUnit's test-class
   * execution order.
   */
  @BeforeEach
  void resetClaimRedeemThrottleCounter() {
    Set<String> keys = redis.keys("khatm:claims:redeem:throttle:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("khatm.keys.soft.keystore-path", KEYSTORE_PATH::toString);
    registry.add("khatm.keys.soft.passphrase", () -> "rbac-http-test-passphrase");
    registry.add("khatm.claims.enc-key", () -> "a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=");
    registry.add("khatm.auth.bootstrap.admin-username", () -> BOOTSTRAP_ADMIN_USERNAME);
    registry.add("khatm.auth.bootstrap.admin-password", () -> BOOTSTRAP_ADMIN_PASSWORD);
    // chore/public-base-url: PublicUrlBuilder fails startup on a blank khatm.public-base-url
    // outside 'local' — this suite runs under 'test'.
    registry.add("khatm.public-base-url", () -> "http://localhost:8080");
    // A short lockout window so DoD #2's "after TTL expires, login works again" is practical to
    // exercise in a test without an artificial sleep longer than a few seconds.
    registry.add("khatm.auth.lockout.max-attempts", () -> "5");
    registry.add("khatm.auth.lockout.window", () -> "2s");
    // Same rationale, for KH-1.2.1's claim-redeem throttle (spec FS-1.2.1 D6) — small numbers so
    // DoD #5's "Nth attempt trips it, after the window it recovers" is practical without a
    // minute-long sleep.
    registry.add("khatm.claims.redeem.throttle.max-attempts", () -> "5");
    registry.add("khatm.claims.redeem.throttle.window", () -> "2s");
  }
}
