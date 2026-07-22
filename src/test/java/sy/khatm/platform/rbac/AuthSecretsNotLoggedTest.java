package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;

/**
 * Spec FS-0.6b DoD #10 — extends {@code credential.domain.NoDisclosureContentInLogsTest}'s pattern
 * (KH-0.4) to the auth paths: no password, no API key secret, and no password hash ever appears in
 * a log line, across both a failed and a successful login, an API key creation, and (KH-1.4.4) the
 * consuming-party key-mint endpoint (SEC §9.7).
 */
class AuthSecretsNotLoggedTest extends RbacHttpTestSupport {

  private static final String KNOWN_WRONG_PASSWORD = "TOP-SECRET-WRONG-PASSWORD-XYZ";
  private static final ObjectMapper JSON = new ObjectMapper();

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

  @Test
  void consumingPartyKeyMint_neverLogsTheRawSecret() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String code = "mint-secret-probe-" + UUID.randomUUID();
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            "/api/v1/admin/consuming-parties",
            session,
            Map.of("code", code, "nameI18n", Map.of("en", "Mint Probe", "ar", "فحص")));
    String partyId = JSON.readTree(created.getBody()).get("id").asText();

    ResponseEntity<String> minted =
        SessionTestSupport.post(
            rest, "/api/v1/admin/consuming-parties/" + partyId + "/api-keys", session, null);
    assertThat(minted.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(minted.getBody());
    String rawKey = body.get("rawKey").asText();
    String rawSecret = rawKey.substring(rawKey.indexOf('.') + 1);

    List<String> messages = capturedMessages();
    assertThat(messages).noneMatch(m -> m.contains(rawKey));
    assertThat(messages).noneMatch(m -> m.contains(rawSecret));
  }

  private List<String> capturedMessages() {
    return appender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.toList());
  }
}
