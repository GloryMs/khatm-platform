package sy.khatm.platform.key.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * <p>Serves every lifecycle state ({@code ACTIVE}/{@code RETIRING}/{@code RETIRED}) — spec FS-0.2
 * §3.2 / FS-2.3 D4: a {@code RETIRED} key stays published for as long as documents signed with it
 * may still need to verify (only a future Phase-3 trust-bundle mechanism will ever drop one out
 * entirely) — and only public JWK material ever appears in the response (no private key component).
 * No authentication: JWKS is public by nature. Cached by clients for {@code max-age=300} seconds,
 * balancing normal-path performance against how quickly an emergency rotation should become
 * visible.
 *
 * <p><b>Deprecated alias, kept alive on purpose (spec FS-2.1 D8, V2; FS-0.4 Amendment A1):</b>
 * since KH-2.1, this path is an alias for the default tenant only — {@link TenantContext#current()}
 * falls back to {@link TenantContext#DEFAULT_TENANT_ID} here because this endpoint is always
 * anonymous ({@code rbac.security.TenantContextFilter} never touches it). Every other tenant's JWKS
 * is served at {@code GET /t/{tenantSlug}/.well-known/jwks.json} ({@code
 * tenant.web.TenantJwksController}). Since Amendment A1, every newly issued credential carries its
 * own {@code jwks_uri} claim pointing at that per-tenant path directly, so this alias is no longer
 * the discovery path new verifiers should reach for. It stays published deliberately as the
 * fallback for every credential issued <em>before</em> A1 (no {@code jwks_uri} claim at all) —
 * cutting it breaks trust bootstrap for every wallet/verifier still resolving one of those. Not
 * removed until pre-A1 credentials have fully expired.
 */
@RestController
@Tag(name = "jwks", description = "Public JSON Web Key Set for signature verification")
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

  /** Public JWKS (every lifecycle state — ACTIVE/RETIRING/RETIRED) — verifiers cache this. */
  @Operation(
      summary = "Fetch the JWKS",
      description =
          "Public signing keys of every lifecycle state (ACTIVE/RETIRING/RETIRED), no"
              + " authentication required. Deprecated (spec FS-2.1 V2): aliases the default tenant"
              + " only — every other tenant's JWKS is at GET /t/{tenantSlug}/.well-known/jwks.json."
              + " Stays available through Phase 2.",
      deprecated = true)
  @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> jwks() {
    List<PublishedKey> keys =
        lifecycle.publishableKeysForDefaultTenantJwks(TenantContext.current());
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
