package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Result of {@code GET /api/v1/activity} (spec FS-1.5.4 #2, KH-1.1.5-BE) — the console's Dashboard
 * v2 recent-activity feed.
 *
 * @param items the most recent activity rows, newest first
 */
@Schema(name = "ActivityResponse", description = "Recent, display-ready credential activity")
public record ActivityResponse(List<ActivityItem> items) {}
