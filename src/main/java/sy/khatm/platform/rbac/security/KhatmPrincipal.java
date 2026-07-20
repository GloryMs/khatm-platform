package sy.khatm.platform.rbac.security;

import java.io.Serializable;
import java.util.UUID;
import sy.khatm.platform.rbac.api.CurrentActor;
import sy.khatm.platform.shared.audit.AuditPrincipal;

/**
 * The {@code Authentication#getPrincipal()} value for every authenticated request — a console
 * session or an API key (spec FS-0.6b §3).
 *
 * <p>Implements {@link AuditPrincipal} so {@code AuditService} can attribute an audit row to this
 * actor without {@code shared} depending on {@code rbac}. Implements {@link Serializable} — Spring
 * Session (Redis, JDK serialization by default, D1) persists the whole {@code SecurityContext},
 * principal included, so a non-serializable principal breaks every session-writing request with a
 * {@code NotSerializableException}, not just the ones an author might expect to touch a session.
 *
 * @param kind which kind of actor this is
 * @param id the actor's id — {@code app_user.id} for {@link CurrentActor.ActorKind#USER}, {@code
 *     api_key.id} for either API-key kind
 * @param tenantId the actor's tenant
 * @param label a human-readable identifier for logging (username, or the key's prefix) — never a
 *     secret
 * @param ownerId the owning {@code consuming_party} row's id ({@link
 *     CurrentActor.ActorKind#API_KEY_CONSUMING_PARTY} only, KH-1.4.3); {@code null} otherwise
 */
record KhatmPrincipal(
    CurrentActor.ActorKind kind, UUID id, UUID tenantId, String label, UUID ownerId)
    implements AuditPrincipal, Serializable {

  @Override
  public String auditActorType() {
    return kind == CurrentActor.ActorKind.USER ? "USER" : "API_KEY";
  }

  @Override
  public UUID auditActorId() {
    return id;
  }

  @Override
  public String toString() {
    return kind + ":" + label;
  }
}
