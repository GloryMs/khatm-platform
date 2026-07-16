package sy.khatm.platform.shared.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for FS-0.6a's HTTP-level error-envelope tests: a real embedded servlet container ({@code
 * WebEnvironment.RANDOM_PORT}), unlike {@code IntegrationTestSupport}, which deliberately pins
 * {@code NONE} for its shared-context, no-HTTP suite (KH-0.2.1). Own dedicated Postgres container —
 * this is a separate context shape from {@code IntegrationTestSupport}'s, so it would never be
 * cached together with that suite regardless.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
abstract class ErrorEnvelopeTestSupport {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add(
        "khatm.keys.soft.keystore-path",
        () -> tempDir.resolve("error-envelope-test-keys.p12").toString());
    registry.add("khatm.keys.soft.passphrase", () -> "error-envelope-test-passphrase");
    registry.add("khatm.claims.enc-key", () -> "a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=");
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
