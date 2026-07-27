package sy.khatm.platform.tenant.web;

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
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.web.ErrorEnvelope;
import sy.khatm.platform.status.api.StatusListArtifact;
import sy.khatm.platform.status.api.StatusListLookup;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

/**
 * Serves the signed status-list artifact at the public well-known URI (spec FS-1.3 D2, SAD §6, spec
 * FS-2.1 D8): {@code GET /sl/{tenantSlug}/{listCode}}.
 *
 * <p><b>Relocated from {@code status.web} (spec FS-2.1 D8):</b> this path is tenant-scoped by URL
 * shape since FS-1.3, but its original implementation only ever compared {@code tenantSlug} against
 * the single default tenant's slug. Making it genuinely resolve any tenant needs a slug lookup —
 * since {@code tenant} already depends one-way on {@code status :: api} (onboarding's default
 * status list), having {@code status} depend back on {@code tenant :: api} to resolve the slug here
 * would be a Modulith module cycle. Relocating the controller here (same fix KH-1.4.4-BE used for
 * its own consuming-party key-mint endpoint) avoids that; {@link StatusListLookup#findArtifact}
 * (widened for this purpose) now owns the lazy-publish-fallback logic this controller used to hold
 * directly.
 *
 * <p>Public and unauthenticated — verifiers fetch this to check a credential's revocation status
 * offline. Cached aggressively ({@code Cache-Control: max-age=60}) and revalidated cheaply via an
 * {@code ETag} carrying the list's {@code version}. Stays available regardless of the owning
 * tenant's suspension status (spec V4).
 */
@RestController
@Tag(name = "status", description = "Signed status-list revocation artifacts")
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class TenantStatusListController {

  private static final MediaType APPLICATION_JOSE = MediaType.valueOf("application/jose");
  private static final Duration CACHE_MAX_AGE = Duration.ofSeconds(60);

  private final TenantDirectory tenants;
  private final StatusListLookup statusLists;
  private final SystemAccessExecutor systemAccess;

  TenantStatusListController(
      TenantDirectory tenants, StatusListLookup statusLists, SystemAccessExecutor systemAccess) {
    this.tenants = tenants;
    this.statusLists = statusLists;
    this.systemAccess = systemAccess;
  }

  @Operation(
      summary = "Fetch a signed status-list artifact",
      description =
          "The public, unauthenticated source of revocation truth for offline verifiers (spec"
              + " FS-1.3 D2, SAD §6). Returns the list's signed bitstring as a compact JWS"
              + " (application/jose) — a verifier validates its signature against the tenant's own"
              + " JWKS, then base64url-decodes and gunzips the `bits` claim to read the"
              + " per-credential revocation bit at the index named in the credential's `status`"
              + " claim. The ETag is the list's `version`; a matching If-None-Match returns 304"
              + " with no body. Cached for 60s.",
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
                    + " (KH-TNT-0404/KH-STS-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping(value = "/sl/{tenantSlug}/{listCode}", produces = "application/jose")
  ResponseEntity<String> getStatusList(
      @Parameter(description = "the tenant's slug", required = true) @PathVariable
          String tenantSlug,
      @Parameter(description = "the status list's code", required = true) @PathVariable
          String listCode,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    TenantRef tenant =
        tenants
            .findBySlug(tenantSlug)
            .orElseThrow(() -> new NotFoundException(ErrorCode.KH_TNT_0404, "tenant.not-found"));

    // KH-2.1 (spec FS-2.1 D5): status_list is RLS-protected and this request has no principal to
    // resolve a tenant from — the target tenant comes from the URL's slug instead, so this read
    // (and the lazy-publish fallback's conditional write) runs under system access.
    StatusListArtifact artifact =
        systemAccess
            .runAsSystem(() -> statusLists.findArtifact(tenant.id(), listCode))
            .orElseThrow(() -> new NotFoundException(ErrorCode.KH_STS_0404, "status.not-found"));

    String eTag = "\"" + artifact.version() + "\"";
    if (ifNoneMatch != null && (ifNoneMatch.equals(eTag) || ifNoneMatch.equals("W/" + eTag))) {
      return ResponseEntity.status(304).eTag(eTag).cacheControl(cache()).build();
    }

    return ResponseEntity.ok()
        .eTag(eTag)
        .cacheControl(cache())
        .contentType(APPLICATION_JOSE)
        .body(artifact.signedArtifact());
  }

  private static CacheControl cache() {
    return CacheControl.maxAge(CACHE_MAX_AGE).cachePublic();
  }
}
