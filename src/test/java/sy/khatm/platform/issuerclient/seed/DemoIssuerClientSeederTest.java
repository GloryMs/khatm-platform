package sy.khatm.platform.issuerclient.seed;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import sy.khatm.platform.issuerclient.domain.IssuerClientLifecycle;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-2.8.1 §4 #14 — the demo issuer client is seeded under the {@code local} profile only, exactly
 * once (a restart against a persisted database must not mint a second one, nor print another key).
 */
@ActiveProfiles({"test", "local"})
class DemoIssuerClientSeederTest extends IntegrationTestSupport {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private IssuerClientLifecycle lifecycle;
  @Autowired private SchemaCatalog schemas;
  @Autowired private ApplicationContext context;

  @Test
  void localProfile_seedsExactlyOneDemoClient_andARerunPrintsNothingNew() throws Exception {
    assertThat(context.containsBean("demoIssuerClientSeeder")).isTrue();
    Integer demoClients =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM issuer_client WHERE name_i18n ->> 'en' = 'Demo Connector"
                + " (local)'",
            Integer.class);
    assertThat(demoClients).isEqualTo(1);

    Logger seederLog = (Logger) LoggerFactory.getLogger(DemoIssuerClientSeeder.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    seederLog.addAppender(appender);
    try {
      new DemoIssuerClientSeeder(lifecycle, schemas).run();
    } finally {
      seederLog.detachAppender(appender);
    }

    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(line -> line.contains("khi_"))
        .anyMatch(line -> line.contains("already exists"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM issuer_client WHERE name_i18n ->> 'en' = 'Demo Connector"
                    + " (local)'",
                Integer.class))
        .isEqualTo(1);
  }
}
