package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.credential.api.ConsumeRequest;
import sy.khatm.platform.credential.api.ConsumeResponse;
import sy.khatm.platform.credential.api.CredentialView;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.api.VerifyRequest;
import sy.khatm.platform.credential.api.VerifyResponse;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * REST controller for credential lifecycle operations.
 *
 * <p>Thin layer: validate → call service → return. No business logic here, and — since KH-0.6a — no
 * ad-hoc error responses either: a not-found credential throws {@link NotFoundException} and lets
 * {@code GlobalExceptionHandler} build the envelope, the same as every other module (spec FS-0.6a
 * D8).
 *
 * <p>Module-private — Spring MVC discovers it via component scan; no other module references this
 * class.
 */
@RestController
@RequestMapping("/api/v1/credentials")
@Tag(name = "credential", description = "Issue, consume, verify, and revoke SD-JWT credentials")
// api-role only (ADR-09): the worker image runs stream consumers and exposes no business REST
// endpoints. matchIfMissing=true keeps this active in every profile that doesn't explicitly set
// khatm.web.enabled=false (i.e. api/test/local/default), so existing web tests are unaffected.
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class CredentialController {

  private final CredentialService service;
  private final MessageSource messageSource;

  CredentialController(CredentialService service, MessageSource messageSource) {
    this.service = service;
    this.messageSource = messageSource;
  }

  @Operation(
      summary = "Issue a new SD-JWT verifiable credential",
      description =
          "Every claim becomes a salted, selectively-disclosable SD-JWT disclosure (spec FS-0.4"
              + " D1) — none of them appear as a plaintext value in the persisted, signed"
              + " payload. The response's sdJwt is a one-time delivery of the full presentation"
              + " (compact JWT plus every disclosure); the platform never stores it in that"
              + " form.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Credential issued"),
        @ApiResponse(
            responseCode = "400",
            description = "Bean Validation failed (e.g. a missing holderRef)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "500",
            description = "Signing failed (KH-KEY-0500) or another unexpected error",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/issue")
  ResponseEntity<IssueResponse> issue(@Valid @RequestBody IssueRequest req) {
    return ResponseEntity.ok(service.issue(req));
  }

  @Operation(
      summary = "Verify an SD-JWT credential presentation",
      description =
          "Accepts the standard tilde-separated SD-JWT presentation, or a bare compact JWT."
              + " Passing a bare JWT with no disclosures at all is a valid zero-disclosure"
              + " presentation (spec FS-0.4 §5) — it will typically (and correctly) fail with"
              + " reason 'withheld_mandatory_claim' unless the schema's sd_fields happens to"
              + " cover every claims_def field. A verification failure is always HTTP 200 with"
              + " valid:false (spec FS-0.6a D1) — it is a domain result, never an error envelope."
              + " Only a completely blank sdJwt is rejected as a 400.",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Verification result (always 200 for a well-formed request)"),
        @ApiResponse(
            responseCode = "400",
            description = "sdJwt was blank or missing",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/verify")
  VerifyResponse verify(@Valid @RequestBody VerifyRequest req) {
    VerifyResponse result = service.verify(req.sdJwt());
    String reasonMessage =
        messageSource.getMessage(
            "verify.reason." + result.reason(), null, LocaleContextHolder.getLocale());
    return new VerifyResponse(
        result.valid(),
        result.reason(),
        reasonMessage,
        result.claims(),
        result.usesRemaining(),
        result.revoked());
  }

  @PostMapping("/consume")
  ConsumeResponse consume(@RequestBody ConsumeRequest req) {
    return service.consume(req);
  }

  @PostMapping("/{id}/revoke")
  ResponseEntity<Void> revoke(@PathVariable String id) {
    boolean ok = service.revoke(UUID.fromString(id));
    if (!ok) {
      throw new NotFoundException(ErrorCode.KH_CRD_0404, "credential.not-found", id);
    }
    return ResponseEntity.ok().build();
  }

  @GetMapping("/{id}")
  ResponseEntity<CredentialView> get(@PathVariable String id) {
    CredentialView view =
        service
            .getView(UUID.fromString(id))
            .orElseThrow(
                () -> new NotFoundException(ErrorCode.KH_CRD_0404, "credential.not-found", id));
    return ResponseEntity.ok(view);
  }
}
