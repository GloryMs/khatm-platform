package sy.khatm.platform.credential.domain;

import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * One consuming party's call-volume/success-rate stats for a window (spec FS-1.5.4 "also needed",
 * KH-1.1.5-BE, {@code GET /api/v1/stats/consuming-parties}) — consumed by {@code
 * credential.web.ConsumingPartyStatsController}.
 *
 * @param partyId the party's internal id
 * @param partyCode the party's machine code
 * @param partyName the party's bilingual display name
 * @param consumed successful {@code CREDENTIAL_CONSUMED} count in the window
 * @param denied {@code CONSUME_SCHEMA_DENIED} count in the window
 * @param successRate {@code consumed / (consumed + denied)}, or {@code 0.0} if both are zero
 */
public record ConsumingPartyStatsView(
    UUID partyId,
    String partyCode,
    LocalizedText partyName,
    long consumed,
    long denied,
    double successRate) {}
