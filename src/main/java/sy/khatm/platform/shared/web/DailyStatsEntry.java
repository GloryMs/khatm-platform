package sy.khatm.platform.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One day's counters within a {@link DailyStatsResponse} (spec FS-1.5.4 #1, KH-1.1.5-BE).
 *
 * @param day the UTC midnight instant this entry's counters were aggregated over
 * @param counters the same {@link StatsCounters} shape {@code GET /api/v1/stats} returns, scoped to
 *     this one day
 */
@Schema(name = "DailyStatsEntry", description = "One UTC day's pilot-metrics counters")
public record DailyStatsEntry(Instant day, StatsCounters counters) {}
