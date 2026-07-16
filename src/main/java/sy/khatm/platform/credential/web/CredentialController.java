package sy.khatm.platform.credential.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
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

/**
 * REST controller for credential lifecycle operations.
 *
 * <p>Thin layer: validate → call service → return. No business logic here.
 *
 * <p>Module-private — Spring MVC discovers it via component scan; no other module references this
 * class.
 */
@RestController
@RequestMapping("/api/v1/credentials")
class CredentialController {

  private final CredentialService service;

  CredentialController(CredentialService service) {
    this.service = service;
  }

  @Operation(
      summary = "Issue a new SD-JWT verifiable credential",
      description =
          "Every claim becomes a salted, selectively-disclosable SD-JWT disclosure (spec FS-0.4"
              + " D1) — none of them appear as a plaintext value in the persisted, signed"
              + " payload. The response's sdJwt is a one-time delivery of the full presentation"
              + " (compact JWT plus every disclosure); the platform never stores it in that"
              + " form.",
      responses = {@ApiResponse(responseCode = "200", description = "Credential issued")})
  @PostMapping("/issue")
  ResponseEntity<IssueResponse> issue(@RequestBody IssueRequest req) throws Exception {
    return ResponseEntity.ok(service.issue(req));
  }

  @Operation(
      summary = "Verify an SD-JWT credential presentation",
      description =
          "Accepts the standard tilde-separated SD-JWT presentation, or a bare compact JWT."
              + " Passing a bare JWT with no disclosures at all is a valid zero-disclosure"
              + " presentation (spec FS-0.4 §5) — it will typically (and correctly) fail with"
              + " reason 'withheld_mandatory_claim' unless the schema's sd_fields happens to"
              + " cover every claims_def field. Other rejection reasons include bad_signature,"
              + " expired, revoked, bad_sd_alg, forged_disclosure, and duplicate_disclosure.",
      responses = {@ApiResponse(responseCode = "200", description = "Verification result")})
  @PostMapping("/verify")
  VerifyResponse verify(@RequestBody VerifyRequest req) {
    return service.verify(req.sdJwt());
  }

  @PostMapping("/consume")
  ConsumeResponse consume(@RequestBody ConsumeRequest req) {
    return service.consume(req);
  }

  @PostMapping("/{id}/revoke")
  ResponseEntity<Void> revoke(@PathVariable String id) {
    boolean ok = service.revoke(UUID.fromString(id));
    return ok ? ResponseEntity.ok().<Void>build() : ResponseEntity.notFound().build();
  }

  @GetMapping("/{id}")
  ResponseEntity<CredentialView> get(@PathVariable String id) {
    return service
        .getView(UUID.fromString(id))
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }
}
