package sy.khatm.platform.rbac.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Batch-resolves {@code api_key} ids to their owner (spec FS-1.5.4 D2) — the gap {@link
 * CurrentActorResolver} deliberately leaves open, since it only resolves the <em>current</em>
 * request's actor, not an arbitrary historical {@code audit_log.actor_id}.
 *
 * <p>{@code credential.web}'s activity/consuming-party-stats endpoints are the first callers: an
 * {@code audit_log} row's {@code actor_id} for a {@code CREDENTIAL_CONSUMED}/{@code
 * CONSUME_SCHEMA_DENIED} event is an {@code api_key.id}, and resolving it to the owning {@code
 * consuming_party} (for a display name via {@code consumer :: api}) needs this lookup.
 */
public interface ApiKeyOwnerLookup {

  /**
   * Resolve a batch of {@code api_key} ids to their owner in one call.
   *
   * @param apiKeyIds the key ids to resolve (duplicates tolerated, order not preserved)
   * @return a map from each resolvable id to its {@link ApiKeyOwnerRef}; an id that no longer
   *     exists (a key row could theoretically be hard-deleted, though nothing does that today) is
   *     simply absent, never a {@code null} entry
   */
  Map<UUID, ApiKeyOwnerRef> resolveOwners(Collection<UUID> apiKeyIds);
}
