package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * One consuming party's stats within a {@link ConsumingPartyStatsResponse} (spec FS-1.5.4 "also
 * needed", KH-1.1.5-BE).
 *
 * @param partyId the party's internal id
 * @param partyCode the party's machine code
 * @param partyName the party's bilingual display name
 * @param consumed successful {@code CREDENTIAL_CONSUMED} count in the window
 * @param denied {@code CONSUME_SCHEMA_DENIED} count in the window
 * @param successRate {@code consumed / (consumed + denied)}, or {@code 0.0} if both are zero
 */
@Schema(name = "ConsumingPartyStatsEntry", description = "One consuming party's call-volume stats")
public record ConsumingPartyStatsEntry(
    UUID partyId,
    String partyCode,
    LocalizedText partyName,
    long consumed,
    long denied,
    double successRate) {}
