package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.2 §5 acceptance criterion 2 — with the {@code local}/{@code dev} profile active, the demo
 * seeder issues one full trial document: a schema, a holder, a credential, and a claim code.
 *
 * <p>This activates the real {@code DemoSeeder} {@code CommandLineRunner} (it only runs under
 * {@code local}/{@code dev}) rather than re-driving the same orchestration by hand, so the test
 * exercises the exact startup path an operator running {@code local}/{@code dev} would see.
 */
@ActiveProfiles({"test", "local"})
class DemoSeederIntegrationTest extends IntegrationTestSupport {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void applicationStartup_underLocalProfile_seedsFullTrialDocument() {
    assertThat(rowCount("credential_schema")).isGreaterThan(0);
    assertThat(rowCount("holder")).isGreaterThan(0);
    assertThat(rowCount("credential")).isGreaterThan(0);
    assertThat(rowCount("claim_code")).isGreaterThan(0);
  }

  private int rowCount(String table) {
    Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return count == null ? 0 : count;
  }
}
