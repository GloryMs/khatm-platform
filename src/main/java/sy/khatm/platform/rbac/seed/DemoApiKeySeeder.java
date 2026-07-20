package sy.khatm.platform.rbac.seed;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import sy.khatm.platform.consumer.api.ConsumingPartyRef;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaSummary;

/**
 * Seeds one demo {@code CONSUMING_PARTY} API key at startup (spec FS-0.6b §4) — lets a developer
 * exercise {@code /consume} end-to-end without building a real onboarding flow first.
 *
 * <p>The bootstrap console admin (username/password) is already provisioned by {@code
 * rbac.domain.AdminBootstrap} (D10) in every profile, including {@code local} — this class only
 * adds what {@code AdminBootstrap} does not: a working API key. {@code credential.seed.DemoSeeder}
 * (the other {@code local}/{@code dev} seeder) stays focused on its own concern, the demo
 * credential issuance flow; this one owns the {@code rbac}-specific artifact, matching Modulith's
 * module- ownership boundary (an {@code api_key} row can only be created through {@code
 * rbac.domain}'s module-private {@link ApiKeyService}).
 *
 * <p><b>Schema allowlisting (KH-1.4.3):</b> {@code /consume} now denies by default unless the
 * calling party's {@code consuming_party_schema} allowlist covers the credential's schema — this
 * seeder allowlists the demo party for {@code DemoSeeder}'s demo schema ({@code
 * CriminalRecordExtract/v1}), found via {@link SchemaCatalog#listAll()} by {@link
 * SchemaSummary#code()}, so the seeded consume-demo keeps working unmodified. {@code @Order(2)}
 * (after {@code DemoSeeder}'s {@code @Order(1)}) guarantees that schema already exists by the time
 * this runs, regardless of Spring's default {@code CommandLineRunner} ordering.
 *
 * <p><b>The raw key is logged in full, once, deliberately</b> — the same local/dev-only,
 * doubly-profile-gated exception {@code DemoSeeder} already uses for its demo claim code. This
 * never runs outside {@code local}/{@code dev}, so it does not weaken SEC §9.7's logging discipline
 * for any real deployment.
 *
 * <p>Active only in {@code local} and {@code dev} Spring profiles. Never runs in production.
 */
@Component
@Profile({"local", "dev"})
@Order(2)
class DemoApiKeySeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoApiKeySeeder.class);
  private static final String DEMO_SCHEMA_CODE = "CriminalRecordExtract/v1";

  private final ApiKeyService apiKeyService;
  private final ConsumingPartyRegistry consumingParties;
  private final SchemaCatalog schemas;

  DemoApiKeySeeder(
      ApiKeyService apiKeyService, ConsumingPartyRegistry consumingParties, SchemaCatalog schemas) {
    this.apiKeyService = apiKeyService;
    this.consumingParties = consumingParties;
    this.schemas = schemas;
  }

  @Override
  public void run(String... args) {
    try {
      ConsumingPartyRef party = consumingParties.ensure("demo-consuming-party");
      CreatedApiKey created =
          apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, party.id(), Set.of("consume"));

      Optional<UUID> demoSchemaId =
          schemas.listAll().stream()
              .filter(schema -> DEMO_SCHEMA_CODE.equals(schema.code()))
              .map(SchemaSummary::id)
              .findFirst();
      if (demoSchemaId.isPresent()) {
        consumingParties.allowSchema(party.id(), demoSchemaId.get());
      } else {
        log.warn(
            "Demo API key seeder: schema {} not found yet, demo consuming party was not"
                + " allowlisted — /consume will 403 for it until it is",
            DEMO_SCHEMA_CODE);
      }

      log.info("========================================================");
      log.info("  Demo consuming-party API key (local/dev only — never for production use):");
      log.info("   rawKey = {}", created.rawKey());
      log.info("========================================================");
    } catch (Exception e) {
      log.warn("Demo API key seeder skipped: {}", e.getMessage());
    }
  }
}
