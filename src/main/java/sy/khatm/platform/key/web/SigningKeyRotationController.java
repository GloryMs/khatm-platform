package sy.khatm.platform.key.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.key.domain.IssuerKeySummary;
import sy.khatm.platform.key.domain.KeyLifecycleService;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Signing-key rotation and retirement for the current tenant (spec FS-2.3 D2/D4, KH-2.3a).
 *
 * <p>Both endpoints act only on the caller's own ambient tenant ({@link TenantContext#current()}),
 * the same as {@link SigningKeyStatusController}'s existing {@code GET} — there is no cross-tenant
 * caller for signing-key lifecycle actions (the per-tenant KMS-provider column, spec V3, is out of
 * scope until KH-2.3b), so neither endpoint needs {@code shared.OnBehalfOfExecutor} or the
 * context-switch-before-transaction pattern documented in {@code docs/CONVENTIONS.md §12}.
 *
 * <p>Gated by {@code rbac.security.SecurityConfig}'s {@code key:manage} rule, same as the existing
 * {@code GET /api/v1/admin/signing-keys}.
 */
@RestController
@Tag(name = "admin-signing-keys", description = "Signing-key lifecycle status for the console")
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class SigningKeyRotationController {

  private final KeyLifecycleService lifecycle;

  SigningKeyRotationController(KeyLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Operation(
      summary = "Rotate the tenant's signing key",
      description =
          "Atomically: generate a new key via the configured KeyProvider, move the current ACTIVE"
              + " key to RETIRING, and activate the new one. The one-ACTIVE-per-tenant partial"
              + " unique index is the final arbiter under concurrent rotations — at most one"
              + " concurrent caller ever succeeds. All of the tenant's status lists are also forced"
              + " stale in the same operation, so the existing periodic sweep re-signs them with"
              + " the new key within one cycle. Requires the key:manage scope (any actor kind).",
      responses = {
        @ApiResponse(responseCode = "200", description = "The newly created, now-ACTIVE key"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Authenticated without the key:manage scope",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/admin/signing-keys/rotate")
  RotateKeyResponse rotate() {
    IssuerKeySummary rotated =
        lifecycle.rotate(TenantContext.current(), TenantContext.currentSlug());
    return new RotateKeyResponse(rotated.kid(), rotated.state(), rotated.validFrom());
  }

  @Operation(
      summary = "Retire a RETIRING signing key",
      description =
          "Moves a RETIRING key to RETIRED. Only a RETIRING key may be retired (409 otherwise)."
              + " Rejected with 422 if the key has not yet reached khatm.keys.min-retiring-age"
              + " (default P30D) unless force=true (bypasses the guard, audited). A RETIRED key"
              + " stays published in JWKS and stays verifiable — retiring never breaks verification"
              + " of documents already signed with it. Requires the key:manage scope (any actor"
              + " kind).",
      responses = {
        @ApiResponse(responseCode = "200", description = "The now-RETIRED key"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Authenticated without the key:manage scope",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No such key for the current tenant (KH-KEY-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "The key is not currently RETIRING (KH-KEY-0409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "422",
            description =
                "The key has not yet reached khatm.keys.min-retiring-age; force=true to bypass"
                    + " (KH-KEY-0422)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/admin/signing-keys/{kid}/retire")
  RetireKeyResponse retire(
      @PathVariable String kid, @RequestBody(required = false) RetireKeyRequest body) {
    boolean force = body != null && body.forceOrDefault();
    IssuerKeySummary retired = lifecycle.retire(kid, force);
    return new RetireKeyResponse(retired.kid(), retired.state(), retired.validTo());
  }
}
