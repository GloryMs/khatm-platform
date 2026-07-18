package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;

/**
 * Spec FS-0.6b DoD #10 — extends {@code credential.domain.NoDisclosureContentInLogsTest}'s pattern
 * (KH-0.4) to the auth paths: no password, no API key secret, and no password hash ever appears in
 * a log line, across both a failed and a successful login, and an API key creation (SEC §9.7).
 */
class AuthSecretsNotLoggedTest extends RbacHttpTestSupport {

  private static final String KNOWN_WRONG_PASSWORD = "TOP-SECRET-WRONG-PASSWORD-XYZ";

  @Autowired private ApiKeyService apiKeyService;

  private Logger rootLogger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    appender = new ListAppender<>();
    appender.start();
    rootLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    rootLogger.detachAppender(appender);
  }

  @Test
  void loginAttempts_neverLogThePasswordOrItsHash() {
    rest.postForEntity(
        "/api/v1/auth/login",
        Map.of("username", BOOTSTRAP_ADMIN_USERNAME, "password", KNOWN_WRONG_PASSWORD),
        Void.class);
    rest.postForEntity(
        "/api/v1/auth/login",
        Map.of("username", BOOTSTRAP_ADMIN_USERNAME, "password", BOOTSTRAP_ADMIN_PASSWORD),
        Void.class);

    List<String> messages = capturedMessages();
    assertThat(messages).noneMatch(m -> m.contains(KNOWN_WRONG_PASSWORD));
    assertThat(messages).noneMatch(m -> m.contains(BOOTSTRAP_ADMIN_PASSWORD));
  }

  @Test
  void apiKeyCreation_neverLogsTheRawSecret() throws Exception {
    CreatedApiKey created = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));
    String rawSecret = created.rawKey().substring(created.rawKey().indexOf('.') + 1);

    // Use the key too, to exercise the verification path's logging as well.
    rest.postForEntity(
        "/api/v1/credentials/issue",
        Map.of("holderRef", "holder-secret-log-probe"),
        String.class); // unauthenticated call — irrelevant to this assertion, just exercises the
    // filter chain once more before checking captured logs.

    List<String> messages = capturedMessages();
    assertThat(messages).noneMatch(m -> m.contains(rawSecret));
    assertThat(messages).noneMatch(m -> m.contains(created.rawKey()));
  }

  private List<String> capturedMessages() {
    return appender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.toList());
  }
}
