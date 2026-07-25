package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

/**
 * One item within an {@link AttentionResponse} (spec FS-1.5.4 #3, KH-1.1.5-BE).
 *
 * @param type {@code SCHEMA_DENIED} or {@code VERIFY_FAILURE_RATE} — see {@code
 *     credential.domain.AttentionItem}'s Javadoc for each type's exact {@code detail} shape
 * @param occurredAt for {@code SCHEMA_DENIED}, the underlying event's time; for {@code
 *     VERIFY_FAILURE_RATE}, effectively "now" (the current window's end)
 * @param detail type-specific fields — never claim content or PII (P1)
 */
@Schema(name = "AttentionEntry", description = "One actionable needs-attention item")
public record AttentionEntry(String type, Instant occurredAt, Map<String, Object> detail) {}
