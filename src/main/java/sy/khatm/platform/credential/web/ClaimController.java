package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.credential.api.ClaimRedeemRequest;
import sy.khatm.platform.credential.api.ClaimRedeemResponse;
import sy.khatm.platform.credential.api.ClaimSchemaRef;
import sy.khatm.platform.credential.domain.ClaimRedeemResult;
import sy.khatm.platform.credential.domain.ClaimRedeemThrottleService;
import sy.khatm.platform.credential.domain.ClaimRedemptionService;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * REST controller for the wallet claim-delivery flow (spec FS-1.2.1).
 *
 * <p>Thin layer: throttle → validate → call service → map. This is the platform's only genuinely
 * public write endpoint alongside {@code /credentials/verify} — authentication here is possession
 * of the claim code itself, not a session or API key (spec §9, {@code
 * rbac.security.SecurityConfig}'s third public entry).
 *
 * <p>Module-private — Spring MVC discovers it via component scan; no other module references this
 * class.
 */
@RestController
@RequestMapping("/api/v1/claims")
@Tag(name = "claim", description = "Wallet claim-code delivery")
// api-role only (ADR-09): same gate as CredentialController — the worker image exposes no
// business REST endpoints.
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class ClaimController {

  private final ClaimRedemptionService redemption;
  private final ClaimRedeemThrottleService throttle;

  ClaimController(ClaimRedemptionService redemption, ClaimRedeemThrottleService throttle) {
    this.redemption = redemption;
    this.throttle = throttle;
  }

  @Operation(
      summary = "Redeem a one-time wallet claim code",
      description =
          "The single-use exchange that hands a freshly issued credential to a wallet: the code is"
              + " consumed atomically (locked, validated, decrypted, marked claimed, and the"
              + " platform's only copy of the disclosures zeroed — all in one transaction, before"
              + " this response is built) so exactly one caller can ever redeem a given code."
              + " Authenticates by possession of the code alone — no session or API key, ever"
              + " (spec FS-1.2.1 §9); public, rate-limited per source address instead."
              + "\n\n**QR contract v1** (spec FS-1.2.1 D8, stable): the QR payload a console issue"
              + " screen renders is the JSON text `{\"v\":1,\"api\":\"<platform base"
              + " URL>\",\"code\":\"<claim code>\"}`. A wallet decodes it and POSTs `code` here,"
              + " against `{api}/api/v1/claims/redeem`. `v` exists so a future incompatible QR"
              + " shape can be introduced without breaking wallets still reading `v:1`."
              + "\n\nThe wallet is contractually obligated to persist the full response immediately"
              + " on receipt, before any display — the platform cannot hand it out a second time."
              + " A lost response after a successful redeem (e.g. the connection drops) is not"
              + " recoverable by retrying; the issuer must create a new claim code from the"
              + " console."
              + "\n\nThe response's `maxUses`/`expiresAt` are a redeem-time snapshot of the"
              + " credential row, for display only. There is deliberately no live \"uses"
              + " remaining\" channel: the holder is anonymous by design (P1 — no PII, no holder"
              + " identity to authorize a lookup against), and a polling endpoint keyed by a"
              + " credential reference would itself be new attack surface. Out of scope by"
              + " design, not an oversight.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Credential delivered"),
        @ApiResponse(
            responseCode = "400",
            description = "Bean Validation failed (a blank code)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description =
                "The code is unknown, malformed, expired, already claimed, or expiry-zeroed —"
                    + " one generic outcome (KH-CLM-0404) for every flavor, so an external caller"
                    + " cannot distinguish 'never existed' from 'someone already claimed it'"
                    + " (spec D5)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "429",
            description =
                "Too many redeem attempts from this source address within the current window"
                    + " (KH-CLM-0429, spec D6)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/redeem")
  ResponseEntity<ClaimRedeemResponse> redeem(
      @Valid @RequestBody ClaimRedeemRequest req, HttpServletRequest request) {
    throttle.enforce(request.getRemoteAddr());
    ClaimRedeemResult result = redemption.redeem(req.code());
    return ResponseEntity.ok(toResponse(result));
  }

  private static ClaimRedeemResponse toResponse(ClaimRedeemResult result) {
    return new ClaimRedeemResponse(
        result.ref(),
        result.credential(),
        result.disclosures(),
        new ClaimSchemaRef(result.schemaId(), result.schemaNameI18n(), result.schemaVersion()),
        result.statusListUri(),
        result.issuedAt(),
        result.maxUses(),
        result.expiresAt());
  }
}
