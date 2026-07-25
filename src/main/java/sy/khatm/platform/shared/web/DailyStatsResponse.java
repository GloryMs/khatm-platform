package sy.khatm.platform.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Result of {@code GET /api/v1/stats/daily} (spec FS-1.5.4 #1, KH-1.1.5-BE) — the console's
 * Dashboard v2 lifecycle chart.
 *
 * @param window the time window {@code days} were aggregated over
 * @param days one entry per UTC day that had at least one event in the window, ascending
 */
@Schema(name = "DailyStatsResponse", description = "Pilot-metrics counters broken down by UTC day")
public record DailyStatsResponse(StatsWindow window, List<DailyStatsEntry> days) {}
