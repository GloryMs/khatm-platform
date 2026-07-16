package sy.khatm.platform.credential.seed;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.ClaimCodeIssued;
import sy.khatm.platform.credential.domain.CredentialService;

/**
 * Seeds one demo credential end-to-end at startup: schema + holder + credential + claim code (spec
 * FS-0.2 §5 acceptance criterion 2).
 *
 * <p>The demo schema's fields are deliberately split between mandatory and withholdable (spec
 * FS-0.4 D2): {@code result} is <em>not</em> in {@code sdFields}, so every presentation must
 * disclose it; {@code caseNumber} and {@code issuedAt} are withholdable at the holder's choice.
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
      Map<String, Object> claims =
          Map.of(
              "result", "NO_RECORD",
              "caseNumber", "CR-2026-00042",
              "issuedAt", "2026-07-16");
      // "result" is left out on purpose — mandatory to disclose in every presentation (D2).
      List<String> withholdableFields = List.of("caseNumber", "issuedAt");

      IssueResponse issued =
          service.issue(
              new IssueRequest(
                  "CriminalRecordExtract/v1",
                  "holder-demo-001",
                  1,
                  60,
                  claims,
                  withholdableFields));
      ClaimCodeIssued claim =
          service.issueClaimCode(
              UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(15));
      log.info("========================================================");
      log.info("  Khatm platform ready. Seeded demo credential:");
      log.info("   id         = {}", issued.id());
      log.info("   ref        = {}", issued.ref());
      log.info("   claimCode  = {} (expires {})", claim.code(), claim.expiresAt());
      log.info("========================================================");
    } catch (Exception e) {
      log.warn("Demo seeder skipped: {}", e.getMessage());
    }
  }
}
