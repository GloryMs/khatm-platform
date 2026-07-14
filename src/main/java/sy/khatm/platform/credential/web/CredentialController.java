package sy.khatm.platform.credential.web;

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

  @PostMapping("/issue")
  ResponseEntity<IssueResponse> issue(@RequestBody IssueRequest req) throws Exception {
    return ResponseEntity.ok(service.issue(req));
  }

  @PostMapping("/verify")
  VerifyResponse verify(@RequestBody VerifyRequest req) {
    return service.verify(req.jwt());
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
