package sy.khatm.platform.issuerclient.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-2.8.1 §4 #14 — outside the {@code local} profile the demo seeder does not exist at all, so no
 * key can ever be printed (or minted) by it in dev/staging/production.
 */
class DemoIssuerClientSeederAbsentOutsideLocalTest extends IntegrationTestSupport {

  @Autowired private ApplicationContext context;

  @Test
  void testProfile_hasNoDemoIssuerClientSeeder() {
    assertThat(context.containsBean("demoIssuerClientSeeder")).isFalse();
  }
}
