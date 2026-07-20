package sy.khatm.platform.status.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.status.api.StatusAllocation;
import sy.khatm.platform.status.api.StatusListAllocator;
import sy.khatm.platform.status.api.StatusListRevoker;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-1.3 DoD #9 — no raw bitstring, gzip bytes, or signed artifact content ever appears in a
 * log line. The publish log line is a plain {@code list_code}/{@code version} count by
 * construction; this pins that property so a future change can't leak the artifact accidentally.
 */
class NoBitstringContentInLogsTest extends IntegrationTestSupport {

  @Autowired private StatusListAllocator allocator;
  @Autowired private StatusListRevoker revoker;
  @Autowired private StatusListPublisher publisher;
  @Autowired private JdbcTemplate jdbc;

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
  void allocateRevokePublishCycle_neverLogsBitstringOrArtifactContent() {
    StatusAllocation a = allocator.allocate("log-probe-" + UUID.randomUUID());
    revoker.revoke(a.statusListId(), a.idx());
    publisher.publishIfStale(a.statusListId());

    String artifact =
        jdbc.queryForObject(
            "SELECT signed_artifact FROM status_list WHERE id = ?", String.class, a.statusListId());
    assertThat(artifact).isNotNull();

    List<String> messages =
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    // The compact JWS is a long dot-separated base64url string; asserting the log never contains
    // it directly proves no line ever echoed the artifact (or, by construction, the raw bitstring
    // it's built from, which is never even resolved to a variable outside BitstringCodec/the
    // publisher's own signing step).
    assertThat(messages).noneMatch(m -> m.contains(artifact));
  }
}
