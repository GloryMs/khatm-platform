package sy.khatm.platform.status.worker;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sy.khatm.platform.status.domain.StatusListPublisher;
import sy.khatm.platform.status.events.StatusListChanged;
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
 */
@Component
@ConditionalOnProperty(name = "khatm.worker.enabled", havingValue = "true")
public class StatusListPublishSweepWorker {

  private static final Logger log = LoggerFactory.getLogger(StatusListPublishSweepWorker.class);

  private final StatusListRepository statusLists;
  private final StatusListPublisher publisher;

  public StatusListPublishSweepWorker(
      StatusListRepository statusLists, StatusListPublisher publisher) {
    this.statusLists = statusLists;
    this.publisher = publisher;
  }

  /**
   * One sweep tick: republish every status list whose artifact is stale or missing.
   *
   * @return how many lists were actually republished this tick, so tests can assert without
   *     re-querying
   */
  @Scheduled(fixedDelayString = "${khatm.status.publish.debounce:2000}")
  public int sweep() {
    List<UUID> staleIds = statusLists.findStaleIds();
    int published = 0;
    for (UUID id : staleIds) {
      if (publisher.publishIfStale(id)) {
        published++;
      }
    }
    if (published > 0) {
      log.debug("status list publish sweep republished {} list(s)", published);
    }
    return published;
  }
}
