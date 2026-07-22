package sy.khatm.platform.shared.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.ValidationException;

/**
 * REST controller for the platform's pilot-metrics counters (KH-1.1.3, spec FS-1.5.3's console C4
 * dashboard commitment).
 *
 * <p>An operator's tool, not an integration API — same session-only stance as {@code GET
 * /api/v1/credentials} (see {@code rbac.security.SecurityConfig}'s Javadoc): any authenticated
 * console session works, no API key of any kind does.
 *
 * <p>Thin: parse/default the window, delegate the actual {@code GROUP BY action} aggregation to
 * {@link AuditService#countActionsInWindow} — this class owns no counting logic of its own.
 *
 * <p>Module-private — Spring MVC discovers it via component scan; no other module references this
 * class.
 */
@RestController
@Tag(name = "stats", description = "Pilot-metrics counters aggregated from the audit trail")
// api-role only (ADR-09), same gate every business controller uses.
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class StatsController {

  private static final int DEFAULT_WINDOW_DAYS = 30;

  private final AuditService audit;

  StatsController(AuditService audit) {
    this.audit = audit;
  }

  @Operation(
      summary = "Fetch pilot-metrics counters",
      description =
          "A plain GROUP BY action aggregation over audit_log for the requested window —"
              + " counters only, never claim content (P1). Defaults to the last 30 days when"
              + " from/to are omitted. Requires a console session — no API key of any kind"
              + " works here (see rbac.security.SecurityConfig's Javadoc).",
      responses = {
        @ApiResponse(responseCode = "200", description = "Counters for the resolved window"),
        @ApiResponse(
            responseCode = "400",
            description = "from/to was present but not a valid ISO-8601 instant",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Authenticated with an API key instead of a console session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/api/v1/stats")
  StatsResponse stats(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    Instant toInstant = parseOrDefault(to, Instant.now());
    Instant fromInstant =
        parseOrDefault(from, toInstant.minus(DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS));

    Map<String, Long> counts = audit.countActionsInWindow(fromInstant, toInstant);
    StatsCounters counters =
        new StatsCounters(
            counts.getOrDefault(AuditAction.CREDENTIAL_ISSUED.name(), 0L),
            counts.getOrDefault(AuditAction.CREDENTIAL_REVOKED.name(), 0L),
            counts.getOrDefault(AuditAction.CREDENTIAL_CONSUMED.name(), 0L),
            counts.getOrDefault(AuditAction.CONSUME_SCHEMA_DENIED.name(), 0L),
            counts.getOrDefault(AuditAction.CLAIM_CODE_REDEEMED.name(), 0L),
            counts.getOrDefault(AuditAction.CREDENTIAL_VERIFY_OK.name(), 0L),
            counts.getOrDefault(AuditAction.CREDENTIAL_VERIFY_FAILED.name(), 0L));
    return new StatsResponse(new StatsWindow(fromInstant, toInstant), counters);
  }

  private static Instant parseOrDefault(String raw, Instant fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new ValidationException(ErrorCode.KH_SYS_0400, "validation.failed");
    }
  }
}
