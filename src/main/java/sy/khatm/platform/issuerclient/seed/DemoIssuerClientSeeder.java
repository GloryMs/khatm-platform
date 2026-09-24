package sy.khatm.platform.issuerclient.seed;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import sy.khatm.platform.issuerclient.domain.CreatedIssuerClient;
import sy.khatm.platform.issuerclient.domain.IssuerClientLifecycle;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Seeds one demo issuer client for the {@code local} profile only (KH-2.8.1) and prints its key —
 * so the M2M walkthrough ({@code scripts/demo-m2m.sh}) works with zero setup.
 *
 * <p><b>Why printing a key here is not a breach of the no-secrets-in-logs rule:</b> the value is a
 * throwaway credential generated on the developer's own machine against a disposable local
 * database, for the {@code local} profile exclusively (this bean does not exist under {@code dev},
 * staging, or production). Every other code path in the platform — including this seeder outside
 * {@code local} — never logs a key, a holder secret, or a {@code holderRef}. Runs after {@code
 * credential.seed.DemoSeeder} so the demo schema exists; a restart against a persisted database
 * finds the client already present and does nothing (a key is shown once, ever).
 */
@Component
@Profile("local")
@Order(2)
class DemoIssuerClientSeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoIssuerClientSeeder.class);
  private static final String DEMO_SCHEMA_CODE = "CriminalRecordExtract/v1";
  private static final String DEMO_NAME_EN = "Demo Connector (local)";

  private final IssuerClientLifecycle lifecycle;
  private final SchemaCatalog schemas;

  DemoIssuerClientSeeder(IssuerClientLifecycle lifecycle, SchemaCatalog schemas) {
    this.lifecycle = lifecycle;
    this.schemas = schemas;
  }

  @Override
  public void run(String... args) {
    try {
      boolean exists = lifecycle.list().stream().anyMatch(c -> DEMO_NAME_EN.equals(c.name().en()));
      if (exists) {
        log.info("Demo issuer client already exists — its key was shown once at first boot.");
        return;
      }
      Optional<SchemaRef> schema = schemas.findByCode(DEMO_SCHEMA_CODE);
      CreatedIssuerClient created =
          lifecycle.create(
              new LocalizedText(DEMO_NAME_EN, "موصِّل تجريبي (محلي)"),
              schema.map(s -> List.of(s.id())).orElse(List.of()),
              null);
      log.info("========================================================");
      log.info("  Demo issuer client (LOCAL PROFILE ONLY — throwaway demo secret):");
      log.info("   keyPrefix  = {}", created.client().keyPrefix());
      log.info("   apiKey     = {}", created.apiKey());
      if (created.holderHmacSecret() != null) {
        log.info("   holderHmac = {}", created.holderHmacSecret());
      }
      log.info("   schema     = {}", DEMO_SCHEMA_CODE);
      log.info("========================================================");
    } catch (Exception e) {
      log.warn("Demo issuer client seeder skipped: {}", e.getMessage());
    }
  }
}
