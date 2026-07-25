package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import sy.khatm.platform.shared.LocalizedText;

/**
 * One recent activity row within an {@link ActivityResponse} (spec FS-1.5.4 #2, KH-1.1.5-BE).
 *
 * @param action the raw {@code AuditAction} name (e.g. {@code CREDENTIAL_CONSUMED})
 * @param actorType {@code USER}, {@code API_KEY}, or {@code SYSTEM}
 * @param entityRef the credential's human-readable {@code ref} — never a bare id, even for actions
 *     whose underlying {@code audit_log} row stores one (spec D3)
 * @param consumingPartyCode the attributed consuming party's machine code, or {@code null} if this
 *     event has no consuming-party attribution
 * @param consumingPartyName the attributed consuming party's bilingual display name, or {@code
 *     null}
 * @param detail the row's raw {@code detail} (e.g. {@code schemaId} for a denied consume) — never
 *     claim content or PII (P1)
 * @param occurredAt when the event was recorded
 */
@Schema(name = "ActivityItem", description = "One recent, display-ready audit event")
public record ActivityItem(
    String action,
    String actorType,
    String entityRef,
    String consumingPartyCode,
    LocalizedText consumingPartyName,
    Map<String, Object> detail,
    Instant occurredAt) {}
