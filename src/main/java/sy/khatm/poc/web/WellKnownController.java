package sy.khatm.poc.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.poc.key.KeyService;

@RestController
public class WellKnownController {

    private final KeyService keys;

    public WellKnownController(KeyService keys) {
        this.keys = keys;
    }

    /** Public JWKS — verifiers cache this to check signatures offline. */
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String jwks() {
        return keys.jwksJson();
    }

    /** Public key in PEM — the Flutter wallet caches this once for on-device offline verify. */
    @GetMapping(value = "/.well-known/pubkey.pem", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> pem() {
        return ResponseEntity.ok(keys.publicKeyPem());
    }
}
