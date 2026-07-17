package sy.khatm.platform.rbac.seed;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import sy.khatm.platform.consumer.api.ConsumingPartyRef;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;

/**
 * Seeds one demo {@code CONSUMING_PARTY} API key at startup (spec FS-0.6b §4) — lets a developer
 * exercise {@code /consume} end-to-end without building a real onboarding flow first (KH-1.4.3).
 *
 * <p>The bootstrap console admin (username/password) is already provisioned by {@code
 * rbac.domain.AdminBootstrap} (D10) in every profile, including {@code local} — this class only
 * adds what {@code AdminBootstrap} does not: a working API key. {@code credential.seed.DemoSeeder}
 * (the other {@code local}/{@code dev} seeder) stays focused on its own concern, the demo
 * credential issuance flow; this one owns the {@code rbac}-specific artifact, matching Modulith's
 * module- ownership boundary (an {@code api_key} row can only be created through {@code
 * rbac.domain}'s module-private {@link ApiKeyService}).
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
class DemoApiKeySeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoApiKeySeeder.class);

  private final ApiKeyService apiKeyService;
  private final ConsumingPartyRegistry consumingParties;

  DemoApiKeySeeder(ApiKeyService apiKeyService, ConsumingPartyRegistry consumingParties) {
    this.apiKeyService = apiKeyService;
    this.consumingParties = consumingParties;
  }

  @Override
  public void run(String... args) {
    try {
      ConsumingPartyRef party = consumingParties.ensure("demo-consuming-party");
      CreatedApiKey created =
          apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, party.id(), Set.of("consume"));
      log.info("========================================================");
      log.info("  Demo consuming-party API key (local/dev only — never for production use):");
      log.info("   rawKey = {}", created.rawKey());
      log.info("========================================================");
    } catch (Exception e) {
      log.warn("Demo API key seeder skipped: {}", e.getMessage());
    }
  }
}
