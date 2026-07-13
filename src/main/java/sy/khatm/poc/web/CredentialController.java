package sy.khatm.poc.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sy.khatm.poc.credential.Credential;
import sy.khatm.poc.credential.CredentialService;
import sy.khatm.poc.credential.dto.Dtos.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class CredentialController {

    private final CredentialService service;

    public CredentialController(CredentialService service) {
        this.service = service;
    }

    @PostMapping("/issue")
    public ResponseEntity<?> issue(@RequestBody IssueRequest req) {
        try {
            return ResponseEntity.ok(service.issue(req));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/verify")
    public VerifyResponse verify(@RequestBody VerifyRequest req) {
        return service.verify(req.jwt());
    }

    @PostMapping("/consume")
    public ConsumeResponse consume(@RequestBody ConsumeRequest req) {
        return service.consume(req);
    }

    @PostMapping("/revoke/{id}")
    public ResponseEntity<?> revoke(@PathVariable String id) {
        boolean ok = service.revoke(UUID.fromString(id));
        return ok ? ResponseEntity.ok(Map.of("revoked", true))
                  : ResponseEntity.notFound().build();
    }

    @GetMapping("/credential/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return service.get(UUID.fromString(id))
                .<ResponseEntity<?>>map(this::toView)
                .orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<?> toView(Credential c) {
        return ResponseEntity.ok(Map.of(
                "id", c.getId().toString(),
                "ref", c.getRef(),
                "schemaCode", c.getSchemaCode(),
                "usesRemaining", c.getUsesRemaining(),
                "maxUses", c.getMaxUses(),
                "revoked", c.isRevoked(),
                "validTo", c.getValidTo().toString(),
                "jwt", c.getSignedJwt()
        ));
    }
}
