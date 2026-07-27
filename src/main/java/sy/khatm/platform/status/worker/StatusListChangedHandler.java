package sy.khatm.platform.status.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.events.StreamEventHandler;
import sy.khatm.platform.status.domain.StatusListPublisher;
import sy.khatm.platform.status.events.StatusListChanged;

/**
 * Consumes {@link StatusListChanged} off the shared {@code khatm.credential.events} stream and
 * attempts an immediate republish of the affected list's signed artifact — the near-real-time half
 * of spec FS-1.3 D3/D5 (the periodic {@code StatusListPublishSweepWorker} is the safety-net half).
 *
 * <p>Always re-reads the list's <em>current</em> row rather than trusting the event payload's own
 * {@code version} — by the time this handler runs, a burst of rapid revokes on the same list may
 * have already advanced the version further still, and {@link StatusListPublisher#publishIfStale}
 * republishes exactly once for whatever the latest state is. This is what collapses a revocation
 * storm into one publish instead of one per event (D5).
 *
 * <p><b>Tenant context (KH-2.1, spec FS-2.1 D5):</b> a worker thread has no principal to resolve a
 * tenant from, so this handler restores {@link TenantContext} from the event payload's {@code
 * tenantId} before touching the (RLS-protected) {@code status_list} row — set/cleared per dispatch,
 * same discipline as the servlet filter's try/finally (a worker thread is reused across unrelated
 * events just as a servlet thread is reused across unrelated requests).
 *
 * <p>Worker-role only ({@code khatm.worker.enabled=true}) — mirrors every other worker-role
 * component's gating (ADR-09); the {@code api} image never registers this bean.
 */
@Component
@ConditionalOnProperty(name = "khatm.worker.enabled", havingValue = "true")
class StatusListChangedHandler implements StreamEventHandler {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final StatusListPublisher publisher;

  StatusListChangedHandler(StatusListPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public String eventType() {
    return StatusListChanged.class.getName();
  }

  @Override
  public void handle(String payload) throws Exception {
    JsonNode node = JSON.readTree(payload);
    UUID statusListId = UUID.fromString(node.get("statusListId").asText());
    UUID tenantId = UUID.fromString(node.get("tenantId").asText());
    // The slug is never read on this path (only KeySignerImpl's id-based key lookup matters here,
    // via TenantContext.current()) — resolving the real slug would need a tenant::api dependency
    // this module deliberately avoids (would cycle with tenant's own dependency on status::api).
    TenantContext.set(tenantId, "");
    try {
      publisher.publishIfStale(statusListId);
    } finally {
      TenantContext.clear();
    }
  }
}
