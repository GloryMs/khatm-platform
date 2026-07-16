package sy.khatm.platform.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * FS-0.6a §5 DoD #8 — a log line emitted in any non-{@code local} profile must be valid, parseable
 * JSON (spec D6). Rather than depending on {@code logback-spring.xml}'s {@code <springProfile>}
 * switching being live in a particular test run's profile, this test encodes a real captured {@link
 * ILoggingEvent} with the exact encoder class ({@code LogstashEncoder}) that file configures for
 * every non-{@code local} profile, and parses the result — the direct, profile-independent way to
 * prove the encoder itself does what D6 requires.
 */
class JsonLogEncodingTest {

  @Test
  void logstashEncoder_encodesACapturedLogLine_asValidJson_withTraceIdAndStandardFields()
      throws Exception {
    Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> capture = new ListAppender<>();
    capture.start();
    rootLogger.addAppender(capture);

    Logger testLogger = (Logger) LoggerFactory.getLogger(JsonLogEncodingTest.class);
    MDC.put("traceId", "json-log-test-trace-id");
    ILoggingEvent captured;
    try {
      testLogger.info("credential issued ref={}", "CRE-2026-000001");
      assertThat(capture.list).isNotEmpty();
      captured = capture.list.get(capture.list.size() - 1);
    } finally {
      MDC.remove("traceId");
      rootLogger.detachAppender(capture);
    }

    LogstashEncoder encoder = new LogstashEncoder();
    encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    encoder.start();
    byte[] encoded;
    try {
      encoded = encoder.encode(captured);
    } finally {
      encoder.stop();
    }

    JsonNode json = new ObjectMapper().readTree(encoded);
    assertThat(json.get("message").asText()).isEqualTo("credential issued ref=CRE-2026-000001");
    assertThat(json.get("traceId").asText()).isEqualTo("json-log-test-trace-id");
    assertThat(json.get("level").asText()).isEqualTo("INFO");
    assertThat(json.has("@timestamp")).isTrue();
  }
}
