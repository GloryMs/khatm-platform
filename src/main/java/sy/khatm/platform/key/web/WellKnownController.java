package sy.khatm.platform.key.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.key.api.KeySigner;

/**
 * Exposes the issuer's public key material at the well-known URIs.
 *
 * <p>Verifiers and mobile wallets fetch these endpoints once and cache the public key for offline
 * signature verification. These endpoints intentionally serve public data only — no auth required.
 */
@RestController
class WellKnownController {

  private final KeySigner keys;

  WellKnownController(KeySigner keys) {
    this.keys = keys;
  }

  /** Public JWKS — verifiers cache this to check signatures offline. */
  @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  String jwks() {
    return keys.jwksJson();
  }

  /** Public key in PEM — the Flutter wallet caches this once for on-device offline verify. */
  @GetMapping(value = "/.well-known/pubkey.pem", produces = MediaType.TEXT_PLAIN_VALUE)
  ResponseEntity<String> pem() {
    return ResponseEntity.ok(keys.publicKeyPem());
  }
}
