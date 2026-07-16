package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.authlete.sd.SDJWT;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.4 §6 DoD #7 — no disclosure value and no salt ever appears in a log line (SEC §9). Captures
 * every log event across a full issue → verify → claim-code cycle and asserts none of the known
 * plaintext claim value or the disclosures' salts appear anywhere in the captured messages.
 */
class NoDisclosureContentInLogsTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;

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
  void issueVerifyAndClaimCodeCycle_neverLogsDisclosureValuesOrSalts() throws Exception {
    Map<String, Object> claims = Map.of("secretValue", "TOP-SECRET-VALUE-XYZ-987");

    IssueResponse issued =
        credentialService.issue(
            new IssueRequest("LogProbe/v1", "holder-log-probe", 1, 60, claims, List.of()));
    credentialService.verify(issued.sdJwt());
    credentialService.issueClaimCode(
        UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));

    List<String> saltsUsed =
        SDJWT.parse(issued.sdJwt()).getDisclosures().stream()
            .map(d -> d.getSalt())
            .collect(Collectors.toList());
    assertThat(saltsUsed).isNotEmpty();

    List<String> messages =
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());

    assertThat(messages).noneMatch(m -> m.contains("TOP-SECRET-VALUE-XYZ-987"));
    for (String salt : saltsUsed) {
      assertThat(messages).noneMatch(m -> m.contains(salt));
    }
  }
}
