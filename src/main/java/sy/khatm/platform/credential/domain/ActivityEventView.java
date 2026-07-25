package sy.khatm.platform.credential.domain;

import java.time.Instant;
import java.util.Map;
import sy.khatm.platform.shared.LocalizedText;

/**
 * One recent audit event, display-ready (spec FS-1.5.4 #2, KH-1.1.5-BE, {@code GET
 * /api/v1/activity}) — consumed by {@code credential.web.ActivityController}.
 *
 * <p>Resolves the two open design points the raw {@code shared.audit.AuditEventView} deliberately
 * leaves to its caller: {@code entityRef} is always a display-ready credential {@code ref} here,
 * never a bare id (spec D3); {@code consumingPartyCode}/{@code consumingPartyName} are populated
 * whenever the row can be attributed to a registered consuming party (spec D2 for {@code
 * CREDENTIAL_CONSUMED}'s {@code actor_id} join, or directly from {@code detail.party} for {@code
 * CONSUME_SCHEMA_DENIED}), {@code null} otherwise — most actions (issuance, claim redemption,
 * verification) have no consuming-party attribution at all.
 *
 * @param action the raw {@code AuditAction} name
 * @param actorType {@code USER}, {@code API_KEY}, or {@code SYSTEM}
 * @param entityRef the credential's human-readable {@code ref} (never a bare UUID)
 * @param consumingPartyCode the attributed party's machine code, or {@code null}
 * @param consumingPartyName the attributed party's bilingual display name, or {@code null}
 * @param detail the row's raw {@code detail}, unchanged
 * @param occurredAt when the event was recorded
 */
public record ActivityEventView(
    String action,
    String actorType,
    String entityRef,
    String consumingPartyCode,
    LocalizedText consumingPartyName,
    Map<String, Object> detail,
    Instant occurredAt) {}
