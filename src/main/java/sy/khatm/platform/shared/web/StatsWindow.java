package sy.khatm.platform.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The time window a {@link StatsResponse}'s counters were aggregated over (KH-1.1.3, {@code GET
 * /api/v1/stats}).
 *
 * @param from inclusive start of the window
 * @param to exclusive end of the window
 */
@Schema(name = "StatsWindow", description = "The time window counters were aggregated over")
public record StatsWindow(Instant from, Instant to) {}
