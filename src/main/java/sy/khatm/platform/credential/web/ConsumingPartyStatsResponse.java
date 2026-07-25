package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import sy.khatm.platform.shared.web.StatsWindow;

/**
 * Result of {@code GET /api/v1/stats/consuming-parties} (spec FS-1.5.4 "also needed", KH-1.1.5-BE)
 * — call volume + success rate per consuming party for a window.
 *
 * @param window the time window {@code parties} were aggregated over
 * @param parties one entry per party with at least one attributable event in the window
 */
@Schema(name = "ConsumingPartyStatsResponse", description = "Per-consuming-party call-volume stats")
public record ConsumingPartyStatsResponse(
    StatsWindow window, List<ConsumingPartyStatsEntry> parties) {}
