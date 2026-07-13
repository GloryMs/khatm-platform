package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.2 §5 acceptance criterion 1 — a clean database, migrated by Flyway, boots the application
 * with {@code ddl-auto: validate} and no errors.
 *
 * <p>The assertion is the successful {@code @SpringBootTest} context refresh itself: if {@code
 * V1__baseline.sql} did not apply cleanly, or any JPA entity in the codebase drifted from the
 * migrated schema, context startup would throw and this test would fail before the body even runs.
 */
class MigrationCleanBootTest extends IntegrationTestSupport {

  @Autowired private ApplicationContext context;

  @Test
  void contextLoads_withFlywayMigratedSchema_andDdlAutoValidate() {
    assertThat(context).isNotNull();
  }
}
