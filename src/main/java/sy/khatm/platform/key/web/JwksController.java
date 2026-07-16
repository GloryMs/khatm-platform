package sy.khatm.platform.key.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.key.domain.KeyLifecycleService;
import sy.khatm.platform.key.domain.PublishedKey;
import sy.khatm.platform.shared.TenantContext;

/**
 * Exposes the default tenant's public signing keys at the standard well-known JWKS URI (spec FS-0.5
 * §6).
 *
 * <p>Serves {@code ACTIVE} and {@code RETIRING} keys only — {@code RETIRED} keys are never
 * published, and only public JWK material ever appears in the response (no private key component).
 * No authentication: JWKS is public by nature. Cached by clients for {@code max-age=300} seconds,
 * balancing normal-path performance against how quickly an emergency rotation should become
 * visible.
 */
@RestController
// api-role only (ADR-09): JWKS is part of the api/verify public surface; the worker image exposes
// no business REST endpoints. See CredentialController for the matchIfMissing rationale.
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class JwksController {

  private static final Duration CACHE_MAX_AGE = Duration.ofMinutes(5);

  private final KeyLifecycleService lifecycle;
  private final ObjectMapper json;

  JwksController(KeyLifecycleService lifecycle, ObjectMapper json) {
    this.lifecycle = lifecycle;
    this.json = json;
  }

  /** Public JWKS ({@code ACTIVE} + {@code RETIRING} keys) — verifiers cache this. */
  @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> jwks() {
    List<PublishedKey> keys = lifecycle.publishableKeys(TenantContext.current());
    ArrayNode keysArray = json.createArrayNode();
    for (PublishedKey key : keys) {
      keysArray.add(readJwk(key.jwkJson()));
    }
    ObjectNode body = json.createObjectNode();
    body.set("keys", keysArray);

    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE))
        .contentType(MediaType.APPLICATION_JSON)
        .body(body.toString());
  }

  private JsonNode readJwk(String jwkJson) {
    try {
      return json.readTree(jwkJson);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored public_jwk is not valid JSON: " + jwkJson, e);
    }
  }
}
