package sy.khatm.platform.shared.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One {@code audit_log} row, shaped for display (spec FS-1.5.4 #2, {@code GET /api/v1/activity}) —
 * {@link AuditService#recentEvents} is the only source of these, since {@code AuditLogEntry} itself
 * is package-private by design (spec FS-0.6b D8).
 *
 * <p>{@code entityRef} and {@code detail} are exactly what the underlying row stored — this record
 * makes no attempt to resolve a credential id to its {@code ref}, or an {@code api_key} actor to
 * its owning consuming party's display name; callers needing that (spec D3/D2) resolve it
 * themselves, since only they know which fields of which actions need it.
 *
 * @param action the raw {@link AuditAction#name()}
 * @param actorType {@code USER}, {@code API_KEY}, or {@code SYSTEM}
 * @param actorId the acting {@code app_user}/{@code api_key} row's id; {@code null} for {@code
 *     SYSTEM}
 * @param entityRef the row's {@code entity_ref}, exactly as stored (may be a ref, a kid, or an id
 *     as a string depending on the action — see {@link AuditAction}'s own Javadoc)
 * @param detail the row's {@code detail}, parsed from JSON; {@code null} when the row had none
 * @param occurredAt when the event was recorded
 */
public record AuditEventView(
    String action,
    String actorType,
    UUID actorId,
    String entityRef,
    Map<String, Object> detail,
    Instant occurredAt) {}
