package sy.khatm.platform.status.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.web.ErrorEnvelope;
import sy.khatm.platform.status.domain.StatusList;
import sy.khatm.platform.status.domain.StatusListPublisher;
import sy.khatm.platform.status.persistence.StatusListRepository;

/**
 * Serves the signed status-list artifact at the public well-known URI (spec FS-1.3 D2, SAD §6):
 * {@code GET /sl/{tenantSlug}/{listCode}}.
 *
 * <p>Public and unauthenticated — verifiers fetch this to check a credential's revocation status
 * offline, validating the compact JWS signature against the platform's JWKS. Cached aggressively
 * ({@code Cache-Control: max-age=60}) and revalidated cheaply via an {@code ETag} carrying the
 * list's {@code version}, so periodic polls from many verifiers collapse to 304s until a revoke
 * bumps the version (NFR-06's ≤60s revoke-to-publish budget matches the cache window on purpose).
 *
 * <p><b>Lazy publish fallback:</b> if a list has never been published ({@code signedArtifact} is
 * still {@code null} — a freshly-allocated list the sweep has not reached yet), the request thread
 * publishes it inline via {@link StatusListPublisher#publishIfStale} before serving, so the
 * endpoint is always available rather than 404-ing during the &lt;2s before the worker sweep first
 * runs. This reuses the exact same publish routine the worker uses; nothing about the JWS
 * construction is duplicated.
 *
 * <p>Module-private — Spring MVC discovers it via component scan; no other module references this
 * class.
 */
@RestController
@Tag(name = "status", description = "Signed status-list revocation artifacts")
// api-role only (ADR-09): the artifact is served by the api image; the worker image exposes no
// business REST endpoints. matchIfMissing keeps this active in api/test/local/default.
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class StatusListController {

  /** RFC 7515 / 7519: the media type for a compact JWS serialization. */
  private static final MediaType APPLICATION_JOSE = MediaType.valueOf("application/jose");

  /** Spec FS-1.3 D2: verifiers poll cheaply within NFR-06's revoke-to-publish window. */
  private static final Duration CACHE_MAX_AGE = Duration.ofSeconds(60);

  private final StatusListRepository statusLists;
  private final StatusListPublisher publisher;

  StatusListController(StatusListRepository statusLists, StatusListPublisher publisher) {
    this.statusLists = statusLists;
    this.publisher = publisher;
  }

  @Operation(
      summary = "Fetch a signed status-list artifact",
      description =
          "The public, unauthenticated source of revocation truth for offline verifiers (spec"
              + " FS-1.3 D2, SAD §6). Returns the list's signed bitstring as a compact JWS"
              + " (application/jose) — a verifier validates its signature against the platform's"
              + " JWKS, then base64url-decodes and gunzips the `bits` claim to read the"
              + " per-credential revocation bit at the index named in the credential's `status`"
              + " claim. The ETag is the list's `version`; a matching If-None-Match returns 304"
              + " with no body, so periodic polls stay cheap until a revoke bumps the version."
              + " Cached for 60s, matching NFR-06's revoke-to-publish budget.",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "The signed status-list artifact",
            headers = {
              @Header(
                  name = "ETag",
                  description = "The list's version, quoted — weak entity tag for revalidation",
                  schema = @Schema(type = "string")),
              @Header(
                  name = "Cache-Control",
                  description = "max-age=60",
                  schema = @Schema(type = "string"))
            }),
        @ApiResponse(
            responseCode = "304",
            description = "The client's If-None-Match matched the current version — body omitted"),
        @ApiResponse(
            responseCode = "404",
            description =
                "No status list at this tenantSlug/listCode, or the tenantSlug is unknown"
                    + " (KH-STS-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping(value = "/sl/{tenantSlug}/{listCode}", produces = "application/jose")
  ResponseEntity<String> getStatusList(
      @Parameter(description = "the tenant's slug", required = true) @PathVariable
          String tenantSlug,
      @Parameter(description = "the status list's code", required = true) @PathVariable
          String listCode,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    // Single-tenant MVP: the only valid slug is the default tenant's. A wrong slug 404s (rather
    // than silently serving another tenant's list); KH-2.1 will resolve a real tenant per request.
    if (!TenantContext.currentSlug().equals(tenantSlug)) {
      throw new NotFoundException(ErrorCode.KH_STS_0404, "status.not-found");
    }

    StatusList list =
        statusLists
            .findByTenantIdAndListCode(TenantContext.current(), listCode)
            .orElseThrow(() -> new NotFoundException(ErrorCode.KH_STS_0404, "status.not-found"));

    // Lazy publish: a list the worker sweep has not reached yet is still servable inline.
    if (list.getSignedArtifact() == null) {
      publisher.publishIfStale(list.getId());
      list =
          statusLists
              .findById(list.getId())
              .orElseThrow(() -> new NotFoundException(ErrorCode.KH_STS_0404, "status.not-found"));
    }

    String eTag = "\"" + list.getVersion() + "\"";
    if (ifNoneMatch != null && (ifNoneMatch.equals(eTag) || ifNoneMatch.equals("W/" + eTag))) {
      return ResponseEntity.status(304).eTag(eTag).cacheControl(cache()).build();
    }

    return ResponseEntity.ok()
        .eTag(eTag)
        .cacheControl(cache())
        .contentType(APPLICATION_JOSE)
        .body(list.getSignedArtifact());
  }

  private static CacheControl cache() {
    return CacheControl.maxAge(CACHE_MAX_AGE).cachePublic();
  }
}
