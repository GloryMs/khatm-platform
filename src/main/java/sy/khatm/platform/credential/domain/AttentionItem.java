package sy.khatm.platform.credential.domain;

import java.time.Instant;
import java.util.Map;

/**
 * One actionable item on the console's Dashboard v2 "needs attention" feed (spec FS-1.5.4 #3,
 * KH-1.1.5-BE) — deliberately itemized, never a bare count ({@code GET /api/v1/stats} already shows
 * raw counts).
 *
 * <p>Ships with two item types this session (spec D5/D6 — a third, "signing key approaching
 * rotation," was descoped: it would need a new {@code key :: api} surface, which this session
 * deliberately does not add):
 *
 * <ul>
 *   <li>{@code SCHEMA_DENIED} — one item per recent {@code CONSUME_SCHEMA_DENIED} row, {@code
 *       detail} carries {@code credentialRef}/{@code schemaId}/{@code schemaCode}/{@code
 *       partyId}/{@code partyCode}/{@code partyName}.
 *   <li>{@code VERIFY_FAILURE_RATE} — at most one item, present only when the current window's
 *       verify-failure rate clears the configured threshold against the immediately preceding
 *       window of the same length; {@code detail} carries both windows' totals/rates and the
 *       thresholds applied.
 * </ul>
 *
 * @param type the item's kind
 * @param occurredAt for {@code SCHEMA_DENIED}, the underlying event's time; for {@code
 *     VERIFY_FAILURE_RATE}, the current window's end (effectively "now")
 * @param detail type-specific fields — never claim content or PII (P1)
 */
public record AttentionItem(String type, Instant occurredAt, Map<String, Object> detail) {

  /** The two item types this session ships (see class Javadoc). */
  public static final String TYPE_SCHEMA_DENIED = "SCHEMA_DENIED";

  public static final String TYPE_VERIFY_FAILURE_RATE = "VERIFY_FAILURE_RATE";
}
