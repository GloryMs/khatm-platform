package sy.khatm.platform.status.worker;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.status.domain.StatusListPublisher;
import sy.khatm.platform.status.events.StatusListChanged;
import sy.khatm.platform.status.persistence.StaleStatusListRef;
import sy.khatm.platform.status.persistence.StatusListRepository;

/**
 * Periodic catch-up sweep for the status-list artifact publish pipeline (spec FS-1.3 D5/§3) — the
 * safety-net half; {@code StatusListChangedHandler} is the near-real-time half. Mirrors {@code
 * credential.worker.ClaimCodeExpiryWorker}'s exact shape: a scheduled tick that finds every row
 * needing attention and processes each, so a {@link StatusListChanged} event that was lost, never
 * wired up, or dead-lettered still gets caught within one sweep interval.
 *
 * <p>Worker-role only ({@code khatm.worker.enabled=true}). The sweep interval is {@code
 * khatm.status.publish.debounce} (default 2000ms) — comfortably inside NFR-06's ≤60s
 * revoke-to-publish budget; tests configure it short to assert the bound directly.
 *
 * <p><b>System access (KH-2.1, spec FS-2.1 D5):</b> this sweep is inherently cross-tenant — it
 * finds and republishes every stale list platform-wide, not scoped to any one tenant — so the whole
 * tick runs under {@link SystemAccessExecutor}.
 */
@Component
@ConditionalOnProperty(name = "khatm.worker.enabled", havingValue = "true")
public class StatusListPublishSweepWorker {

  private static final Logger log = LoggerFactory.getLogger(StatusListPublishSweepWorker.class);

  private final StatusListRepository statusLists;
  private final StatusListPublisher publisher;
  private final SystemAccessExecutor systemAccess;

  public StatusListPublishSweepWorker(
      StatusListRepository statusLists,
      StatusListPublisher publisher,
      SystemAccessExecutor systemAccess) {
    this.statusLists = statusLists;
    this.publisher = publisher;
    this.systemAccess = systemAccess;
  }

  /**
   * One sweep tick: republish every status list whose artifact is stale or missing.
   *
   * @return how many lists were actually republished this tick, so tests can assert without
   *     re-querying
   */
  @Scheduled(fixedDelayString = "${khatm.status.publish.debounce:2000}")
  public int sweep() {
    return systemAccess.runAsSystem(
        () -> {
          List<StaleStatusListRef> stale = statusLists.findStaleRefs();
          int published = 0;
          for (StaleStatusListRef ref : stale) {
            // Each list must be signed with its OWN tenant's key (key.domain.KeySignerImpl reads
            // only the ambient TenantContext, never a parameter) — the sweep itself runs under
            // SystemAccessExecutor precisely so it can see every tenant's stale lists in one
            // query, but that bypass says nothing about which tenant's key to sign with.
            TenantContext.set(ref.tenantId(), "");
            try {
              if (publisher.publishIfStale(ref.id())) {
                published++;
              }
            } finally {
              TenantContext.clear();
            }
          }
          if (published > 0) {
            log.debug("status list publish sweep republished {} list(s)", published);
          }
          return published;
        });
  }
}
