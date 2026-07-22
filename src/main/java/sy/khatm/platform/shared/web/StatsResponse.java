package sy.khatm.platform.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of {@code GET /api/v1/stats} (KH-1.1.3) — the console's C4 dashboard commitment (spec
 * FS-1.5.3).
 *
 * @param window the time window {@code counters} were aggregated over
 * @param counters the pilot-metrics counters for that window
 */
@Schema(name = "StatsResponse", description = "Pilot-metrics counters for a time window")
public record StatsResponse(StatsWindow window, StatsCounters counters) {}
