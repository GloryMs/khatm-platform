package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.credential.domain.AttentionItem;
import sy.khatm.platform.credential.domain.AttentionService;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Exposes the console's Dashboard v2 needs-attention feed (spec FS-1.5.4 #3, KH-1.1.5-BE).
 *
 * <p>Thin: all threshold/window logic lives in {@link AttentionService}, computed on read.
 */
@RestController
@Tag(name = "attention", description = "Itemized, actionable needs-attention feed")
// api-role only (ADR-09), same gate every business controller uses.
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class AttentionController {

  private final AttentionService attention;

  AttentionController(AttentionService attention) {
    this.attention = attention;
  }

  @Operation(
      summary = "Fetch the needs-attention feed",
      description =
          "Itemized, actionable anomalies computed on read from the audit trail — never a bare"
              + " count (see GET /api/v1/stats for those). Ships two item types this session:"
              + " recent CONSUME_SCHEMA_DENIED events within a configurable window, and a"
              + " verify-failure-rate alert when the current window's failure rate clears a"
              + " configurable multiplier of the immediately preceding window's baseline (with a"
              + " minimum-volume floor to avoid noise). A third starter type (signing key"
              + " approaching rotation) is deliberately out of scope this session. Requires a"
              + " console session — no API key of any kind works here (same gate as"
              + " GET /api/v1/stats).",
      responses = {
        @ApiResponse(responseCode = "200", description = "Current needs-attention items"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Authenticated with an API key instead of a console session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/api/v1/attention")
  AttentionResponse attention() {
    return new AttentionResponse(
        attention.attention().stream().map(AttentionController::toEntry).toList());
  }

  private static AttentionEntry toEntry(AttentionItem item) {
    return new AttentionEntry(item.type(), item.occurredAt(), item.detail());
  }
}
