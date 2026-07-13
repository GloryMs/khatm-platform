package sy.khatm.platform.support;

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

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
