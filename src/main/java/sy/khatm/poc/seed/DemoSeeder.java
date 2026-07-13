package sy.khatm.poc.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import sy.khatm.poc.credential.CredentialService;
import sy.khatm.poc.credential.dto.Dtos.IssueRequest;
import sy.khatm.poc.credential.dto.Dtos.IssueResponse;

import java.util.Map;

@Component
public class DemoSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    private final CredentialService service;

    public DemoSeeder(CredentialService service) {
        this.service = service;
    }

    @Override
    public void run(String... args) {
        try {
            IssueResponse r = service.issue(new IssueRequest(
                    "CriminalRecordExtract/v1",
                    "holder-demo-001",
                    1,      // single use
                    60,     // valid 60 minutes
                    Map.of("fullName", "Demo Citizen", "result", "NO_RECORD")
            ));
            log.info("========================================================");
            log.info("  Khatm POC ready. Seeded demo credential:");
            log.info("   id  = {}", r.id());
            log.info("   ref = {}", r.ref());
            log.info("  Open the web console at http://localhost:5173");
            log.info("========================================================");
        } catch (Exception e) {
            log.warn("Seeder skipped: {}", e.getMessage());
        }
    }
}
