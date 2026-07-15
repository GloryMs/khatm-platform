package sy.khatm.platform.key.domain;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.TenantContext;

/**
 * Auto-provisions the default tenant's first {@code ACTIVE} issuer key at startup (spec FS-0.5 §5).
 *
 * <p>Runs in every profile — production included — because the platform cannot sign anything
 * without at least one {@code ACTIVE} key, and there is no other provisioning path yet. Idempotent:
 * a second startup against a database that already has an {@code ACTIVE} key for the tenant does
 * nothing (spec FS-0.5 §8.7).
 *
 * <p><b>Temporary by design:</b> Phase 2 replaces this with an explicit administrative provisioning
 * ceremony (key custodian approval, multi-party sign-off per SEC's key-management requirements) —
 * auto-provisioning on first boot is only acceptable while there is a single platform-default
 * tenant and no console/RBAC to gate it. Do not extend this class to provision additional tenants;
 * that path should go through the future ceremony instead.
 */
@Component
class KeyBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(KeyBootstrap.class);

  private final KeyLifecycleService lifecycle;

  KeyBootstrap(KeyLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Override
  public void run(ApplicationArguments args) {
    Optional<IssuerKeySummary> created =
        lifecycle.bootstrapIfNeeded(TenantContext.current(), TenantContext.currentSlug());
    if (created.isPresent()) {
      log.info("Bootstrapped issuer key kid={}", created.get().kid());
    } else {
      log.info("Active issuer key already present for default tenant — bootstrap skipped");
    }
  }
}
