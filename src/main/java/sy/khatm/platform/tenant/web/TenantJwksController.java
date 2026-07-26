package sy.khatm.platform.tenant.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.key.api.JwksLookup;
import sy.khatm.platform.key.api.PublishedKeyView;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.web.ErrorEnvelope;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

/**
 * Exposes any tenant's public signing keys at a per-tenant well-known JWKS URI (spec FS-2.1 D8).
 *
 * <p>Deliberately lives in {@code tenant.web}, not {@code key.web}: {@code tenant} already depends
 * one-way on {@code key :: api} (onboarding, {@code key.api.TenantKeyProvisioner}), so having
 * {@code key} depend back on {@code tenant :: api} to resolve {@code tenantSlug} here would be a
 * Modulith module cycle. The legacy default-tenant-only {@code GET /.well-known/jwks.json} stays in
 * {@code key.web.JwksController}, unchanged.
 *
 * <p>Public and unauthenticated, same as the legacy path and the status-list artifact endpoint —
 * suspension never affects this (spec V4: suspending a tenant blocks new issuance only).
 */
@RestController
@Tag(name = "jwks", description = "Public JSON Web Key Set for signature verification")
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class TenantJwksController {

  private static final Duration CACHE_MAX_AGE = Duration.ofMinutes(5);

  private final TenantDirectory tenants;
  private final JwksLookup jwksLookup;
  private final ObjectMapper json;

  TenantJwksController(TenantDirectory tenants, JwksLookup jwksLookup, ObjectMapper json) {
    this.tenants = tenants;
    this.jwksLookup = jwksLookup;
    this.json = json;
  }

  @Operation(
      summary = "Fetch a tenant's JWKS",
      description =
          "Public ACTIVE + RETIRING signing keys for the named tenant, no authentication required."
              + " Stays available regardless of the tenant's suspension status (spec V4).",
      responses = {
        @ApiResponse(responseCode = "200", description = "The tenant's JWKS"),
        @ApiResponse(
            responseCode = "404",
            description = "No tenant with this slug (KH-TNT-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping(
      value = "/t/{tenantSlug}/.well-known/jwks.json",
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> jwks(
      @Parameter(description = "the tenant's slug", required = true) @PathVariable
          String tenantSlug) {
    TenantRef tenant =
        tenants
            .findBySlug(tenantSlug)
            .orElseThrow(() -> new NotFoundException(ErrorCode.KH_TNT_0404, "tenant.not-found"));

    List<PublishedKeyView> keys = jwksLookup.publishableKeys(tenant.id());
    ArrayNode keysArray = json.createArrayNode();
    for (PublishedKeyView key : keys) {
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
