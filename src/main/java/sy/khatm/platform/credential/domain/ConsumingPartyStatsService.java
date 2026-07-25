package sy.khatm.platform.credential.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import sy.khatm.platform.consumer.api.ConsumingPartyAdmin;
import sy.khatm.platform.consumer.api.ConsumingPartyView;
import sy.khatm.platform.rbac.api.ApiKeyOwnerLookup;
import sy.khatm.platform.rbac.api.ApiKeyOwnerRef;
import sy.khatm.platform.rbac.api.ApiKeyOwnerRef.OwnerKind;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Backs {@code GET /api/v1/stats/consuming-parties} (spec FS-1.5.4 "also needed", KH-1.1.5-BE) —
 * call volume + success rate per consuming party for a window. Shares the exact same {@code
 * actor_id -> consuming_party} resolution gap (and its {@code rbac :: api ApiKeyOwnerLookup}/{@code
 * consumer :: api ConsumingPartyAdmin} solution, spec D2/D4) that {@link ActivityService} solves
 * for the activity feed — solving it once unblocks both endpoints, exactly as the brief predicted.
 *
 * <p>{@code public} Java visibility only for cross-package access from {@code credential.web}
 * within this same module (same stance as {@link ActivityService}).
 */
@Service
public class ConsumingPartyStatsService {

  private static final List<String> ACTIONS =
      List.of("CREDENTIAL_CONSUMED", "CONSUME_SCHEMA_DENIED");
  private static final String ACTION_CONSUMED = "CREDENTIAL_CONSUMED";
  private static final String ACTION_DENIED = "CONSUME_SCHEMA_DENIED";

  private final AuditService audit;
  private final ApiKeyOwnerLookup ownerLookup;
  private final ConsumingPartyAdmin consumingParties;

  public ConsumingPartyStatsService(
      AuditService audit, ApiKeyOwnerLookup ownerLookup, ConsumingPartyAdmin consumingParties) {
    this.audit = audit;
    this.ownerLookup = ownerLookup;
    this.consumingParties = consumingParties;
  }

  /**
   * Per-consuming-party call volume and success rate within {@code [from, to)}.
   *
   * <p>Multiple {@code api_key} rows owned by the same party (e.g. a rotated key plus its
   * predecessor) are summed into one entry — the aggregation key is the party, not the key.
   *
   * @return one entry per party that had at least one {@code CREDENTIAL_CONSUMED}/{@code
   *     CONSUME_SCHEMA_DENIED} event attributable to it in the window; a {@code TENANT}-owned key's
   *     activity (which has no consuming party to attribute to) is excluded, not folded in
   */
  public List<ConsumingPartyStatsView> statsForWindow(Instant from, Instant to) {
    Map<UUID, Map<String, Long>> byActor = audit.actorActionCounts(from, to, ACTIONS);
    if (byActor.isEmpty()) {
      return List.of();
    }

    Map<UUID, ApiKeyOwnerRef> owners = ownerLookup.resolveOwners(byActor.keySet());
    Map<UUID, long[]> byParty = aggregateByParty(byActor, owners);

    Map<UUID, ConsumingPartyView> partiesById = new HashMap<>();
    consumingParties.list().forEach(p -> partiesById.put(p.id(), p));

    List<ConsumingPartyStatsView> views = new ArrayList<>();
    for (Map.Entry<UUID, long[]> entry : byParty.entrySet()) {
      ConsumingPartyView party = partiesById.get(entry.getKey());
      if (party == null) {
        continue;
      }
      long consumed = entry.getValue()[0];
      long denied = entry.getValue()[1];
      long total = consumed + denied;
      double successRate = total == 0 ? 0.0 : (double) consumed / total;
      views.add(
          new ConsumingPartyStatsView(
              party.id(), party.code(), party.nameI18n(), consumed, denied, successRate));
    }
    return views;
  }

  private static Map<UUID, long[]> aggregateByParty(
      Map<UUID, Map<String, Long>> byActor, Map<UUID, ApiKeyOwnerRef> owners) {
    Map<UUID, long[]> byParty = new HashMap<>();
    for (Map.Entry<UUID, Map<String, Long>> entry : byActor.entrySet()) {
      ApiKeyOwnerRef owner = owners.get(entry.getKey());
      if (owner == null || owner.kind() != OwnerKind.CONSUMING_PARTY) {
        continue;
      }
      long[] agg = byParty.computeIfAbsent(owner.ownerId(), k -> new long[2]);
      agg[0] += entry.getValue().getOrDefault(ACTION_CONSUMED, 0L);
      agg[1] += entry.getValue().getOrDefault(ACTION_DENIED, 0L);
    }
    return byParty;
  }
}
