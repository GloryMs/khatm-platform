package sy.khatm.platform.status.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import sy.khatm.platform.key.events.KeyRotated;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.events.StreamEventHandler;
import sy.khatm.platform.status.persistence.StatusListRepository;

/**
 * Consumes {@link KeyRotated} off the shared {@code khatm.credential.events} stream and forces
 * every one of the rotated tenant's status lists stale (spec FS-2.3 D3) — bumping {@code version}
 * so it no longer matches {@code artifactVersion}. Does the resign itself: the already-running
 * {@code StatusListPublishSweepWorker} picks up the now-stale rows on its next tick and re-signs
 * each with whatever key is {@code ACTIVE} for that tenant by then (the new one, since rotation has
 * already committed by the time this handler runs).
 *
 * <p>{@code status} already depends on {@code key :: api} (for {@code KeySigner}); consuming a
 * second public type from {@code key} — this time its {@code events} sub-package — does not add a
 * new Modulith dependency edge, only a new type crossing the one that already exists. {@code key}
 * itself never depends on {@code status} (would be a cycle) — it only publishes the event, unaware
 * of who, if anyone, consumes it.
 *
 * <p>Tenant context: same discipline as {@code StatusListChangedHandler} — a worker thread has no
 * principal to resolve a tenant from, so this handler restores {@link TenantContext} from the event
 * payload's {@code tenantId} (RLS-scoped, no slug needed for a bulk update by id) before touching
 * {@code status_list}, set/cleared per dispatch.
 *
 * <p>Worker-role only ({@code khatm.worker.enabled=true}) — mirrors every other worker-role
 * component's gating (ADR-09); the {@code api} image never registers this bean.
 */
@Component
@ConditionalOnProperty(name = "khatm.worker.enabled", havingValue = "true")
class KeyRotationHandler implements StreamEventHandler {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final StatusListRepository statusLists;

  KeyRotationHandler(StatusListRepository statusLists) {
    this.statusLists = statusLists;
  }

  @Override
  public String eventType() {
    return KeyRotated.class.getName();
  }

  @Override
  public void handle(String payload) throws Exception {
    JsonNode node = JSON.readTree(payload);
    UUID tenantId = UUID.fromString(node.get("tenantId").asText());
    TenantContext.set(tenantId, "");
    try {
      statusLists.bumpVersionForTenant(tenantId);
    } finally {
      TenantContext.clear();
    }
  }
}
