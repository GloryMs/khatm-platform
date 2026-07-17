package sy.khatm.platform.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for Testcontainers-backed integration tests (KH-0.2.1 acceptance criteria, spec FS-0.2
 * §5).
 *
 * <p>Uses the Testcontainers "singleton container" pattern: one Postgres container is started once
 * per JVM in the static initializer and never explicitly stopped — Testcontainers' Ryuk reaper
 * cleans it up when the JVM exits. Combined with identical {@code @DynamicPropertySource} values
 * across subclasses, Spring's test context cache reuses a single {@code ApplicationContext} for
 * every test class that extends this one under the {@code test} profile, so the suite only boots
 * the app (and runs {@code V1__baseline.sql} via Flyway) once.
 *
 * <p>Every table created by the baseline migration is exercised through the real application
 * context here — {@code ddl-auto: validate} would fail the context refresh if any entity drifted
 * from the migrated schema, which is exactly what {@code MigrationCleanBootTest} asserts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

  protected static final PostgreSQLContainer<?> POSTGRES;

  /**
   * KH-0.5: the shared test context needs a working {@code khatm.keys.soft.*} config too, since the
   * active profile here is {@code test}, not {@code local} — {@code SoftKeyProvider} fails startup
   * on a blank passphrase outside {@code local} by design (spec FS-0.5 §3/§8.5). One keystore file
   * for the whole shared-context test suite, same rationale as one Postgres container: every test
   * class using this base reuses the same cached {@code ApplicationContext}.
   */
  private static final Path TEST_KEYSTORE_PATH;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
    try {
      TEST_KEYSTORE_PATH = Files.createTempFile("khatm-test-keys-", ".p12");
      Files.deleteIfExists(TEST_KEYSTORE_PATH);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @DynamicPropertySource
  static void keyProviderProperties(DynamicPropertyRegistry registry) {
    registry.add("khatm.keys.soft.keystore-path", TEST_KEYSTORE_PATH::toString);
    registry.add("khatm.keys.soft.passphrase", () -> "khatm-test-passphrase");
  }

  /**
   * KH-0.4: same rationale as {@link #keyProviderProperties} — {@code ClaimsEncryptionService}
   * fails startup on a blank {@code khatm.claims.enc-key} outside {@code local} (spec FS-0.4 D7).
   * 32 raw bytes, base64-encoded (AES-256 requires exactly that length).
   */
  @DynamicPropertySource
  static void claimsEncryptionProperties(DynamicPropertyRegistry registry) {
    registry.add("khatm.claims.enc-key", () -> "a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=");
  }

  /**
   * KH-0.6b: same rationale again — {@code AdminBootstrap} fails startup on a blank {@code
   * khatm.auth.bootstrap.admin-username}/{@code admin-password} outside {@code local} (spec FS-0.6b
   * D10), and this shared-context suite runs under {@code test}, not {@code local}.
   */
  @DynamicPropertySource
  static void adminBootstrapProperties(DynamicPropertyRegistry registry) {
    registry.add("khatm.auth.bootstrap.admin-username", () -> "test-admin");
    registry.add("khatm.auth.bootstrap.admin-password", () -> "test-admin-password-change-me");
  }
}
