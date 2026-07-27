package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.credential.api.BulkIssueItemError;
import sy.khatm.platform.credential.api.BulkIssueItemResult;
import sy.khatm.platform.credential.api.BulkIssueRequest;
import sy.khatm.platform.credential.api.BulkIssueResponse;
import sy.khatm.platform.credential.api.ClaimCodeMintRequest;
import sy.khatm.platform.credential.api.ClaimCodeMintResponse;
import sy.khatm.platform.credential.api.ConsumeRequest;
import sy.khatm.platform.credential.api.ConsumeResponse;
import sy.khatm.platform.credential.api.CredentialPage;
import sy.khatm.platform.credential.api.CredentialView;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.api.VerifyRequest;
import sy.khatm.platform.credential.api.VerifyResponse;
import sy.khatm.platform.credential.domain.BulkIssuanceService;
import sy.khatm.platform.credential.domain.BulkIssueItemOutcome;
import sy.khatm.platform.credential.domain.BulkIssueOutcome;
import sy.khatm.platform.credential.domain.ClaimCodeIssued;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.KhatmException;
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
  private final BulkIssuanceService bulkIssuance;
  private final MessageSource messageSource;
  private final AuditService audit;
  private final SystemAccessExecutor systemAccess;

  CredentialController(
      CredentialService service,
      BulkIssuanceService bulkIssuance,
      MessageSource messageSource,
      AuditService audit,
      SystemAccessExecutor systemAccess) {
    this.service = service;
    this.bulkIssuance = bulkIssuance;
    this.messageSource = messageSource;
    this.audit = audit;
    this.systemAccess = systemAccess;
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
      summary = "Issue a batch of credentials against one schema",
      description =
          "The C3 console wizard's CSV-per-schema flow (KH-1.1.3) — up to 200 items, one schema"
              + " per batch, each issued independently through the same single-issue path (no"
              + " parallel issuance logic, no bypass of any single-issue guard). One bad row"
              + " never rolls back the batch: the response reports every item's outcome by"
              + " index, ISSUED or FAILED with its own error. Setting mintClaimCodes:true mints"
              + " a one-time wallet claim code for every successfully issued item (the existing"
              + " POST /{id}/claim-code path) and returns it in that item's result — shown here"
              + " exactly once. Requires the same scope as /issue: session or TENANT API key"
              + " (a CONSUMING_PARTY key is always 403 here).",
      responses = {
        @ApiResponse(responseCode = "200", description = "Per-item report (always 200)"),
        @ApiResponse(
            responseCode = "400",
            description =
                "The batch itself is invalid — empty items, or more than 200 (KH-CRD-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the issue scope, or called with a CONSUMING_PARTY API key instead of a"
                    + " console session or TENANT API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/bulk")
  BulkIssueResponse bulkIssue(@Valid @RequestBody BulkIssueRequest req) {
    BulkIssueOutcome outcome = bulkIssuance.bulkIssue(req);
    return new BulkIssueResponse(
        outcome.total(),
        outcome.succeeded(),
        outcome.failed(),
        outcome.results().stream().map(this::toItemResult).toList());
  }

  private BulkIssueItemResult toItemResult(BulkIssueItemOutcome r) {
    if (r.succeeded()) {
      return new BulkIssueItemResult(r.index(), "ISSUED", r.id(), r.ref(), r.claimCode(), null);
    }
    KhatmException error = r.error();
    String message =
        messageSource.getMessage(error.messageKey(), error.args(), LocaleContextHolder.getLocale());
    return new BulkIssueItemResult(
        r.index(),
        "FAILED",
        null,
        null,
        null,
        new BulkIssueItemError(error.errorCode().code(), message));
  }

  @Operation(
      summary = "Search/list credentials",
      description =
          "Console-facing search over this tenant's credentials (KH-1.1.4) — proof/status"
              + " metadata rows only, never claim content (P1 rule). Every filter is optional"
              + " and AND-combined: ref (exact), pseudoRef (exact, resolved against the holder"
              + " who was issued the credential), schemaId (exact), revoked (exact). Sorted by"
              + " issuedAt descending; page/size are zero-based/1-100, defaulting to 0/20."
              + " Requires a console session — no API key of any kind works here (see"
              + " rbac.security.SecurityConfig's Javadoc).",
      responses = {
        @ApiResponse(responseCode = "200", description = "Matching page of credential summaries"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Authenticated with an API key instead of a console session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping
  CredentialPage list(
      @RequestParam(required = false) String ref,
      @RequestParam(required = false) String pseudoRef,
      @RequestParam(required = false) String schemaId,
      @RequestParam(required = false) Boolean revoked,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    UUID schemaUuid = schemaId == null ? null : UUID.fromString(schemaId);
    return service.search(ref, pseudoRef, schemaUuid, revoked, page, size);
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
    // KH-2.1 (spec FS-2.1 D5): /verify is genuinely anonymous — a presented credential may belong
    // to any tenant, so this lookup runs under system access rather than any one tenant's RLS
    // scope. The audit write below stays outside it, attributed to the default tenant like any
    // other unscoped write.
    VerifyResponse result = systemAccess.runAsSystem(() -> service.verify(req.sdJwt()));
    String reasonMessage =
        messageSource.getMessage(
            "verify.reason." + result.reason(), null, LocaleContextHolder.getLocale());

    // KH-1.1.3 D6: recorded here, deliberately outside CredentialService#verify's own
    // readOnly=true transaction — a read-only transaction cannot accept this write. `ref` comes
    // from the already-decoded structural claims (never re-parsed), and is null whenever verify()
    // never reached a credential row (malformed, bad signature, unknown kid).
    String ref = result.claims() == null ? null : (String) result.claims().get("ref");
    audit.record(
        result.valid() ? AuditAction.CREDENTIAL_VERIFY_OK : AuditAction.CREDENTIAL_VERIFY_FAILED,
        "credential",
        ref,
        Map.of("reason", result.reason()));

    return new VerifyResponse(
        result.valid(),
        result.reason(),
        reasonMessage,
        result.claims(),
        result.usesRemaining(),
        result.revoked(),
        result.statusListChecked(),
        result.statusListVersion(),
        result.statusListUri());
  }

  @Operation(
      summary = "Consume one use of a credential",
      description =
          "The atomic double-spend guard: a single-transaction conditional UPDATE decrements the"
              + " remaining uses only if the credential is still ACTIVE and unexpired, so exactly"
              + " one caller wins under concurrency. Requires the consume scope and a"
              + " CONSUMING_PARTY API key (a console session, or a TENANT key even with the"
              + " consume scope, is always 403 here). The caller's consuming party must also be"
              + " scoped to the credential's schema via consuming_party_schema (KH-1.4.3,"
              + " deny-by-default — an unconfigured party can consume nothing). An optional"
              + " idempotencyKey makes repeated calls with the same key return the first outcome"
              + " without re-running the UPDATE.",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description =
                "Consumption outcome (accepted, already-exhausted, expired, or revoked"
                    + " — always a domain result, never an error envelope for a normal denial)"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the consume scope, called with a console session or a TENANT API key"
                    + " instead of a CONSUMING_PARTY key (KH-RBC-0403), or the credential's schema"
                    + " is not in the calling party's allowlist (KH-CNS-0403, KH-1.4.3)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/consume")
  ConsumeResponse consume(@RequestBody ConsumeRequest req) {
    // KH-1.4.3: deliberately called before, not from inside, service.consume(req) — see
    // CredentialService#enforceSchemaAllowlist's Javadoc for why the denial-path audit row needs
    // its own, non-nested transaction.
    service.enforceSchemaAllowlist(req.id());
    return service.consume(req);
  }

  @Operation(
      summary = "Revoke a credential",
      description =
          "Immediately and permanently invalidates the credential — a subsequent /verify or"
              + " /consume call reports it revoked. Requires the revoke scope and a console"
              + " session (an API key is always 403 here). Irreversible.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Revoked"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the revoke scope, or called with an API key instead of a"
                    + " console session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No credential with this id (KH-CRD-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/revoke")
  ResponseEntity<Void> revoke(@PathVariable String id) {
    boolean ok = service.revoke(UUID.fromString(id));
    if (!ok) {
      throw new NotFoundException(ErrorCode.KH_CRD_0404, "credential.not-found", id);
    }
    return ResponseEntity.ok().build();
  }

  @Operation(
      summary = "Mint a fresh wallet claim code for an already-issued credential",
      description =
          "The console-facing counterpart to POST /issue's one-time claim-code delivery — spec"
              + " FS-1.2.1 D2's explicit recovery path: if a code never reached the wallet (lost"
              + " response, expired unclaimed, or the issuer simply wants to hand the credential"
              + " out again), the issuer mints a new one here rather than re-issuing the whole"
              + " credential. Requires the exact sdJwt presentation string the original /issue"
              + " call returned — the platform never stores disclosures outside a claim_code row"
              + " (P1), so this endpoint cannot reconstruct them on its own; the caller is expected"
              + " to have retained that one-time delivery for exactly this purpose."
              + "\n\nMinting voids any prior still-live code for this credential (its"
              + " disclosures_enc is zeroed, the same mechanism the redeem and expiry-sweep paths"
              + " use) — at most one code is ever redeemable per credential at a time."
              + "\n\nThe response's code is a one-time delivery, exactly like /issue's sdJwt: it"
              + " appears in this response and nowhere else, ever. It is what a console issue"
              + " screen encodes into the QR v1 payload described on POST"
              + " /api/v1/claims/redeem — {\"v\":1,\"api\":\"<platform base URL>\",\"code\":\"<this"
              + " code>\"} — for a wallet to scan and redeem at that endpoint.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Claim code minted"),
        @ApiResponse(
            responseCode = "400",
            description = "Bean Validation failed (a blank sdJwt)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description =
                "Missing the issue scope, or called with a CONSUMING_PARTY API key instead of a"
                    + " console session or TENANT API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No credential with this id (KH-CRD-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description =
                "The credential is revoked or has expired and can no longer accept a claim code"
                    + " (KH-CRD-0409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/claim-code")
  ResponseEntity<ClaimCodeMintResponse> mintClaimCode(
      @PathVariable String id, @Valid @RequestBody ClaimCodeMintRequest req) {
    ClaimCodeIssued minted =
        service.mintClaimCode(UUID.fromString(id), req.sdJwt(), req.ttlMinutes());
    return ResponseEntity.ok(new ClaimCodeMintResponse(minted.code(), minted.expiresAt()));
  }

  @Operation(
      summary = "Fetch a credential's current status",
      description =
          "Proof/status metadata only (ref, schema, status, uses remaining, timestamps) — never the"
              + " claim values themselves, which only ever leave the platform inside a one-time"
              + " sdJwt presentation (P1 rule). Requires any valid session or API key.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Credential status"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No credential with this id (KH-CRD-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
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
