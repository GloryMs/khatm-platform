package sy.khatm.platform.credential.seed;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.CredentialService;

/**
 * Seeds one demo credential at startup.
 *
 * <p>Active only in {@code local} and {@code dev} Spring profiles. Never runs in production.
 */
@Component
@Profile({"local", "dev"})
class DemoSeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

  private final CredentialService service;

  DemoSeeder(CredentialService service) {
    this.service = service;
  }

  @Override
  public void run(String... args) {
    try {
      IssueResponse r =
          service.issue(
              new IssueRequest(
                  "CriminalRecordExtract/v1",
                  "holder-demo-001",
                  1,
                  60,
                  Map.of("result", "NO_RECORD")));
      log.info("========================================================");
      log.info("  Khatm platform ready. Seeded demo credential:");
      log.info("   id  = {}", r.id());
      log.info("   ref = {}", r.ref());
      log.info("========================================================");
    } catch (Exception e) {
      log.warn("Demo seeder skipped: {}", e.getMessage());
    }
  }
}
