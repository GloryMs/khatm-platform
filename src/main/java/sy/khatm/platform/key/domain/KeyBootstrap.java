package sy.khatm.platform.key.domain;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.TenantContext;

/**
 * Auto-provisions the default tenant's first {@code ACTIVE} issuer key at startup (spec FS-0.5 §5).
 *
 * <p>Runs in the {@code api} role only — production included — because the platform cannot sign
 * anything without at least one {@code ACTIVE} key, and there is no other provisioning path yet.
 * Idempotent: a second startup against a database that already has an {@code ACTIVE} key for the
 * tenant does nothing (spec FS-0.5 §8.7).
 *
 * <p><b>{@code khatm.web.enabled} gate (discovered KH-0.3-closure, fixed here):</b> {@code api} and
 * {@code worker} share one {@code khatm_keys} PKCS#12 volume. Before this gate, both roles ran this
 * {@code ApplicationRunner} unconditionally, so a genuinely concurrent first boot was a real race —
 * whichever process lost saw the {@code issuer_key} row already {@code ACTIVE} and skipped its own
 * bootstrap, but its {@link sy.khatm.platform.key.domain.SoftKeyProvider} had already loaded its
 * in-memory {@code KeyStore} from the still-empty keystore file at ITS OWN startup, before the
 * winner's write was visible — leaving the loser signing against a {@code kid} its own JVM never
 * held. Gating this the same way {@code CredentialController}/{@code JwksController} already are
 * (ADR-09) means only the {@code api} role ever bootstraps, so there is only ever one writer.
 *
 * <p><b>Temporary by design:</b> Phase 2 replaces this with an explicit administrative provisioning
 * ceremony (key custodian approval, multi-party sign-off per SEC's key-management requirements) —
 * auto-provisioning on first boot is only acceptable while there is a single platform-default
 * tenant and no console/RBAC to gate it. Do not extend this class to provision additional tenants;
 * that path should go through the future ceremony instead.
 */
@Component
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
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
