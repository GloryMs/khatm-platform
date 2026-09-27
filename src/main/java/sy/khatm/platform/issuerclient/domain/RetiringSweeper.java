package sy.khatm.platform.issuerclient.domain;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

/**
 * Worker-role sweep that finishes rotations (spec FS-2.7a D10, ADR-09): every {@code RETIRING}
 * client whose {@code retire_after} has passed becomes {@code REVOKED}, with an {@code
 * ISSUER_CLIENT_REVOKED} audit row ({@code detail.reason = retired}).
 *
 * <p>The candidate list is read under system access (a cross-tenant sweep, like {@code
 * ClaimCodeExpiryWorker}); each revocation then runs under that client's <em>own</em> tenant
 * context so its audit row lands in the right tenant's trail. Registered only on the worker role
 * ({@code khatm.worker.enabled=true}), never on the api role. Authentication already refuses a
 * client past {@code retire_after} on its own — this sweep only makes the stored status agree.
 */
@Component
@ConditionalOnProperty(name = "khatm.worker.enabled", havingValue = "true")
public class RetiringSweeper {

  private static final Logger log = LoggerFactory.getLogger(RetiringSweeper.class);

  private final IssuerClientRepository repository;
  private final IssuerClientLifecycle lifecycle;
  private final SystemAccessExecutor systemAccess;
  private final TenantDirectory tenants;
  private final Clock clock;

  RetiringSweeper(
      IssuerClientRepository repository,
      IssuerClientLifecycle lifecycle,
      SystemAccessExecutor systemAccess,
      TenantDirectory tenants,
      Clock clock) {
    this.repository = repository;
    this.lifecycle = lifecycle;
    this.systemAccess = systemAccess;
    this.tenants = tenants;
    this.clock = clock;
  }

  /**
   * One sweep pass.
   *
   * @return how many clients were revoked
   */
  @Scheduled(fixedDelayString = "${khatm.worker.issuer-client.retire-sweep-ms:60000}")
  public int sweep() {
    List<IssuerClient> due =
        systemAccess.runAsSystem(() -> repository.findRetiringDue(clock.instant()));
    int revoked = 0;
    for (IssuerClient client : due) {
      TenantRef tenant = tenants.findById(client.getTenantId()).orElse(null);
      if (tenant == null) {
        continue;
      }
      TenantContext.set(tenant.id(), tenant.slug());
      try {
        if (lifecycle.finishRetirement(client.getId())) {
          revoked++;
        }
      } finally {
        TenantContext.clear();
      }
    }
    if (revoked > 0) {
      log.info("issuer-client retiring sweep revoked {} client(s)", revoked);
    }
    return revoked;
  }
}
