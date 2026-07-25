package sy.khatm.platform.rbac.api;

import java.util.UUID;

/**
 * Who one {@code api_key} row acts on behalf of (spec FS-1.5.4 D2) — the batch counterpart to
 * {@link CurrentActor#kind()}/{@link CurrentActor#ownerId()} for a historical {@code
 * audit_log.actor_id}, which {@link CurrentActorResolver} cannot resolve (it only knows the
 * <em>current</em> request's actor).
 *
 * @param kind whether the key is owned by the tenant itself or by a registered consuming party
 * @param ownerId the owning {@code consuming_party} row's id ({@link OwnerKind#CONSUMING_PARTY}
 *     only); {@code null} for {@link OwnerKind#TENANT}, mirroring {@code api_key.owner_id}
 */
public record ApiKeyOwnerRef(OwnerKind kind, UUID ownerId) {

  /** Mirrors {@code rbac.domain.ApiKeyOwnerType} without exposing that module-private type. */
  public enum OwnerKind {
    TENANT,
    CONSUMING_PARTY
  }
}
