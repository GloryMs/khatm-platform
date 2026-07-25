package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Result of {@code GET /api/v1/attention} (spec FS-1.5.4 #3, KH-1.1.5-BE) — the console's Dashboard
 * v2 needs-attention feed. Itemized, actionable items only — {@code GET /api/v1/stats} already
 * shows raw counts, so this endpoint deliberately never repeats them.
 *
 * @param items the current actionable items; empty when nothing needs attention
 */
@Schema(name = "AttentionResponse", description = "Itemized, actionable needs-attention feed")
public record AttentionResponse(List<AttentionEntry> items) {}
