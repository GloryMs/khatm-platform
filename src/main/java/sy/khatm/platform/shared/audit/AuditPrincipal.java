package sy.khatm.platform.shared.audit;

import java.util.UUID;

/**
 * The minimal contract {@link AuditService} needs from the current {@code SecurityContextHolder}'s
 * {@code Authentication#getPrincipal()} to attribute an audit row to a {@code USER} or {@code
 * API_KEY} actor (spec FS-0.6b D8).
 *
 * <p>{@code shared} has no dependency on {@code rbac} (it is the other way around), so this small
 * SPI — implemented by {@code rbac}'s Spring Security principal types — is how the actor's identity
 * crosses the module boundary without {@code shared.audit} ever importing {@code rbac} types. The
 * same pattern {@code shared.events.StreamEventHandler} already uses for the ADR-09 consumer SPI.
 *
 * <p>When the current {@code Authentication}'s principal does not implement this interface (no
 * session, no API key, or a request-scoped worker/system task with no {@code SecurityContext} at
 * all), {@link AuditService} attributes the row to {@code SYSTEM} instead.
 */
public interface AuditPrincipal {

  /**
   * The actor type to store in {@code audit_log.actor_type} — must be exactly {@code "USER"} or
   * {@code "API_KEY"} (the table's {@code CHECK} constraint also allows {@code "SYSTEM"}, which
   * {@link AuditService} applies itself as the no-principal fallback, never via this method).
   *
   * @return {@code "USER"} or {@code "API_KEY"}
   */
  String auditActorType();

  /**
   * The actor's id to store in {@code audit_log.actor_id} — the {@code app_user} row for a {@code
   * USER} actor, the {@code api_key} row for an {@code API_KEY} actor.
   *
   * @return the actor's id
   */
  UUID auditActorId();
}
