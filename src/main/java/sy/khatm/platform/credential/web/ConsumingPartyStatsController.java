package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.credential.domain.ConsumingPartyStatsService;
import sy.khatm.platform.credential.domain.ConsumingPartyStatsView;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.shared.web.ErrorEnvelope;
import sy.khatm.platform.shared.web.StatsWindow;

/**
 * Exposes per-consuming-party call-volume/success-rate stats for the console's Dashboard v2 (spec
 * FS-1.5.4 "also needed", KH-1.1.5-BE) — lives under {@code /api/v1/stats/**} so it picks up {@code
 * rbac.security.SecurityConfig}'s existing widened stats gate, no separate entry.
 */
@RestController
@Tag(name = "stats", description = "Per-consuming-party call-volume stats")
// api-role only (ADR-09), same gate every business controller uses.
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class ConsumingPartyStatsController {

  private static final int DEFAULT_WINDOW_DAYS = 30;

  private final ConsumingPartyStatsService stats;

  ConsumingPartyStatsController(ConsumingPartyStatsService stats) {
    this.stats = stats;
  }

  @Operation(
      summary = "Fetch per-consuming-party call-volume stats",
      description =
          "Call volume (CREDENTIAL_CONSUMED) and denial count (CONSUME_SCHEMA_DENIED) per"
              + " consuming party for the requested window, with a derived success rate. Defaults"
              + " to the last 30 days when from/to are omitted. Same session-only gate as"
              + " GET /api/v1/stats.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Per-party stats for the resolved window"),
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
  @GetMapping("/api/v1/stats/consuming-parties")
  ConsumingPartyStatsResponse consumingPartyStats(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    Instant toInstant = parseOrDefault(to, Instant.now());
    Instant fromInstant =
        parseOrDefault(from, toInstant.minus(DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS));

    var parties =
        stats.statsForWindow(fromInstant, toInstant).stream()
            .map(ConsumingPartyStatsController::toEntry)
            .toList();
    return new ConsumingPartyStatsResponse(new StatsWindow(fromInstant, toInstant), parties);
  }

  private static ConsumingPartyStatsEntry toEntry(ConsumingPartyStatsView view) {
    return new ConsumingPartyStatsEntry(
        view.partyId(),
        view.partyCode(),
        view.partyName(),
        view.consumed(),
        view.denied(),
        view.successRate());
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
