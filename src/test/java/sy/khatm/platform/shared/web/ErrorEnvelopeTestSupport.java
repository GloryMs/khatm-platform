package sy.khatm.platform.shared.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import sy.khatm.platform.support.TransactionalTestJdbcTemplateConfig;

/**
 * Base for FS-0.6a's HTTP-level error-envelope tests: a real embedded servlet container ({@code
 * WebEnvironment.RANDOM_PORT}), unlike {@code IntegrationTestSupport}, which deliberately pins
 * {@code NONE} for its shared-context, no-HTTP suite (KH-0.2.1). Own dedicated Postgres container —
 * this is a separate context shape from {@code IntegrationTestSupport}'s, so it would never be
 * cached together with that suite regardless.
 *
 * <p><b>Deliberately not {@code @Testcontainers}/{@code @Container}</b> (KH-1.6-early — the same
 * fix {@code RbacHttpTestSupport} already needed): those JUnit5 annotations bind the container's
 * {@code start()}/{@code stop()} lifecycle to the <em>owning test class's</em> {@code
 * beforeAll}/{@code afterAll}, including for a {@code static} field merely <em>inherited</em> from
 * this abstract base. With a second subclass added ({@code OpenApiContractTest}, alongside the
 * original {@code ErrorEnvelopeAndI18nTest}) sharing this base's identical
 * {@code @DynamicPropertySource} values — and therefore Spring's cached {@code ApplicationContext}
 * — the first subclass to finish stopped the container out from under the second, which then failed
 * with {@code CannotCreateTransactionException} against a dead connection pool (confirmed
 * empirically, the exact failure mode {@code RbacHttpTestSupport}'s own Javadoc already describes).
 * Same fix: start the container once in a manual {@code static} initializer and never explicitly
 * stop it — Testcontainers' Ryuk reaper cleans up at JVM exit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TransactionalTestJdbcTemplateConfig.class)
abstract class ErrorEnvelopeTestSupport {

  static final PostgreSQLContainer<?> POSTGRES;
  static final Path TEMP_DIR;

  static {
    POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            // KH-2.1 (spec FS-2.1 D3): provisions khatm_app before Flyway's first migration run —
            // V7__rls_policies.sql GRANTs to it, so it must already exist.
            .withInitScript("db/khatm-app-role-init.sql");
    POSTGRES.start();
    try {
      TEMP_DIR = Files.createTempDirectory("khatm-error-envelope-test-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    // KH-2.1 (spec FS-2.1 D3): same role split as support.IntegrationTestSupport.
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "khatm_app");
    registry.add("spring.datasource.password", () -> "khatm-app-test-only-password");
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add(
        "khatm.keys.soft.keystore-path",
        () -> TEMP_DIR.resolve("error-envelope-test-keys.p12").toString());
    registry.add("khatm.keys.soft.passphrase", () -> "error-envelope-test-passphrase");
    registry.add("khatm.claims.enc-key", () -> "a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=");
    // KH-0.6b: AdminBootstrap fails startup on a blank admin-username/password outside 'local'
    // (spec FS-0.6b D10) — this suite runs under 'test'.
    registry.add("khatm.auth.bootstrap.admin-username", () -> "test-admin");
    registry.add("khatm.auth.bootstrap.admin-password", () -> "test-admin-password-change-me");
    // chore/public-base-url: PublicUrlBuilder fails startup on a blank khatm.public-base-url
    // outside 'local' — this suite runs under 'test'.
    registry.add("khatm.public-base-url", () -> "http://localhost:8080");
  }

  /** A block of test code to run while a {@link ListAppender} is attached to the root logger. */
  interface LoggedBlock {
    void run(ListAppender<ILoggingEvent> appender) throws Exception;
  }

  /**
   * Attach a {@link ListAppender} to the root Logback logger for the duration of {@code block},
   * then detach it — same technique as {@code NoDisclosureContentInLogsTest} (KH-0.4).
   */
  static void withCapturedLogs(LoggedBlock block) throws Exception {
    Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    rootLogger.addAppender(appender);
    try {
      block.run(appender);
    } finally {
      rootLogger.detachAppender(appender);
    }
  }
}
