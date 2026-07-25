package sy.khatm.platform.key.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.key.domain.IssuerKeyStatusView;
import sy.khatm.platform.key.domain.KeyLifecycleService;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Exposes the tenant's signing keys' lifecycle status for the console's Dashboard v2 signing-key
 * panel (spec FS-1.5.4 #4, KH-1.1.5-BE).
 *
 * <p>Unlike {@link JwksController} (public, {@code ACTIVE}/{@code RETIRING} only, public JWK
 * material), this is an admin-only, full-lifecycle view — every state including {@code RETIRED} —
 * of the key's status fields only, never any key material. Gated by {@code
 * rbac.security.SecurityConfig}'s existing {@code /api/v1/admin/**} wildcard ({@code admin} scope,
 * any actor kind) — no new {@code SecurityConfig} entry needed.
 */
@RestController
@Tag(name = "admin-signing-keys", description = "Signing-key lifecycle status for the console")
// api-role only (ADR-09), same gate every business controller uses.
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class SigningKeyStatusController {

  private final KeyLifecycleService lifecycle;

  SigningKeyStatusController(KeyLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Operation(
      summary = "List every signing key's lifecycle status",
      description =
          "Every signing key for the tenant, newest first, every state including RETIRED —"
              + " lifecycle fields only (kid/state/validFrom/validTo), never the public JWK or any"
              + " private material. Requires the admin scope (any actor kind).",
      responses = {
        @ApiResponse(responseCode = "200", description = "Every signing key's lifecycle status"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Authenticated without the admin scope",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/api/v1/admin/signing-keys")
  SigningKeysResponse signingKeys() {
    return new SigningKeysResponse(
        lifecycle.listAllStatuses(TenantContext.current()).stream().map(this::toView).toList());
  }

  private SigningKeyView toView(IssuerKeyStatusView key) {
    return new SigningKeyView(key.kid(), key.state(), key.validFrom(), key.validTo());
  }
}
