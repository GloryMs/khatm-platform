package sy.khatm.platform.tenant.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import sy.khatm.platform.key.events.KeyRotated;
import sy.khatm.platform.shared.events.StreamEventHandler;
import sy.khatm.platform.tenant.persistence.TenantRepository;

/**
 * Consumes {@link KeyRotated} off the shared {@code khatm.credential.events} stream and updates
 * {@code tenant.key_provider} to the new {@code ACTIVE} key's provider (spec FS-2.3 D5/D6, veto V3)
 * — the same event {@code status.worker.KeyRotationHandler} already consumes for an unrelated
 * reason (forcing status lists stale). {@code tenant} already depends on {@code key :: api} (for
 * {@code TenantKeyProvisioner}/{@code JwksLookup}); consuming a second public type from {@code key}
 * — this time its {@code events} sub-package — does not add a new Modulith dependency edge, only a
 * new type crossing the one that already exists. {@code key} itself never depends on {@code tenant}
 * (would be a cycle) — it only publishes the event, unaware of who, if anyone, consumes it.
 *
 * <p>{@code tenant} itself is excluded from RLS (spec FS-2.1 D2), so no {@code TenantContext} setup
 * is needed here, unlike {@code status.worker.KeyRotationHandler}'s own handler.
 *
 * <p>Worker-role only ({@code khatm.worker.enabled=true}) — mirrors every other worker-role
 * component's gating (ADR-09); the {@code api} image never registers this bean.
 */
@Component
@ConditionalOnProperty(name = "khatm.worker.enabled", havingValue = "true")
class TenantKeyProviderSyncHandler implements StreamEventHandler {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final TenantRepository tenants;

  TenantKeyProviderSyncHandler(TenantRepository tenants) {
    this.tenants = tenants;
  }

  @Override
  public String eventType() {
    return KeyRotated.class.getName();
  }

  @Override
  public void handle(String payload) throws Exception {
    JsonNode node = JSON.readTree(payload);
    UUID tenantId = UUID.fromString(node.get("tenantId").asText());
    String provider = node.get("provider").asText();
    tenants.updateKeyProvider(tenantId, provider);
  }
}
