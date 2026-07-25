package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.credential.domain.ActivityEventView;
import sy.khatm.platform.credential.domain.ActivityService;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Exposes the console's Dashboard v2 recent-activity feed (spec FS-1.5.4 #2, KH-1.1.5-BE).
 *
 * <p>Thin: parse/default {@code limit}/{@code event}, delegate the resolution work (entity ref,
 * consuming-party attribution) to {@link ActivityService} — this class owns no composition logic of
 * its own, matching {@code shared.web.StatsController}'s stance.
 */
@RestController
@Tag(name = "activity", description = "Recent, display-ready credential activity feed")
// api-role only (ADR-09), same gate every business controller uses.
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class ActivityController {

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final ActivityService activity;

  ActivityController(ActivityService activity) {
    this.activity = activity;
  }

  @Operation(
      summary = "Fetch the recent-activity feed",
      description =
          "The most recent credential-lifecycle audit events (issued/consumed/revoked/"
              + "schema-denied/claim-redeemed/verify-ok/verify-failed), newest first, resolved for"
              + " display: entity_ref is always the credential's ref (never a bare id), and"
              + " consuming-party attribution is resolved where the underlying event supports it."
              + " event, if present, is a comma-separated subset of AuditAction names to filter to"
              + " (unknown values are ignored); omit it for every eligible action. Requires a"
              + " console session — no API key of any kind works here (same gate as"
              + " GET /api/v1/stats).",
      responses = {
        @ApiResponse(responseCode = "200", description = "Recent activity, newest first"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Authenticated with an API key instead of a console session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/api/v1/activity")
  ActivityResponse activity(
      @RequestParam(required = false) Integer limit, @RequestParam(required = false) String event) {
    int resolvedLimit = clampLimit(limit);
    List<String> actions = parseActions(event);

    List<ActivityItem> items =
        activity.recent(resolvedLimit, actions).stream().map(ActivityController::toItem).toList();
    return new ActivityResponse(items);
  }

  private static int clampLimit(Integer requested) {
    if (requested == null || requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  private static List<String> parseActions(String event) {
    if (event == null || event.isBlank()) {
      return List.of();
    }
    return Arrays.stream(event.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private static ActivityItem toItem(ActivityEventView view) {
    return new ActivityItem(
        view.action(),
        view.actorType(),
        view.entityRef(),
        view.consumingPartyCode(),
        view.consumingPartyName(),
        view.detail(),
        view.occurredAt());
  }
}
