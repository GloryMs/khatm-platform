package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.consumer.api.ConsumingPartyAdmin;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Mints a {@code CONSUMING_PARTY}-owned API key for a registered consuming party (KH-1.4.4, {@code
 * POST /api/v1/admin/consuming-parties/{id}/api-keys}).
 *
 * <p><b>Why this endpoint lives in {@code rbac}, not {@code consumer}:</b> only the {@code rbac}
 * module may create {@code api_key} rows ({@link ApiKeyService} is module-private to {@code
 * rbac.domain}), and {@code consumer} cannot depend on {@code rbac} without forming a module cycle
 * — {@code rbac} already depends on {@code consumer :: api}. So the key-mint verb sits here (path
 * still under {@code /api/v1/admin/**}, so the {@code admin}-scope gate covers it), while the rest
 * of the consuming-party admin plane lives in {@code consumer.web}. This class validates the party
 * exists via {@link ConsumingPartyAdmin#get} (which 404s as {@code KH-CNS-0404} otherwise) before
 * minting.
 *
 * <p>The minted key carries the single {@code consume} scope. The raw value is shown exactly once
 * (spec FS-0.6b §4) — the platform stores only its SHA-256 hash. Revocation reuses the existing
 * {@code POST /api/v1/admin/api-keys/{id}/revoke}.
 *
 * <p>Module-private — Spring MVC discovers it via component scan; no other module references it.
 */
@RestController
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
@Tag(
    name = "consuming-parties-admin",
    description = "Consuming-party (verifier) administration — registry, status, schema allowlist")
class ConsumingPartyKeyController {

  private final ConsumingPartyAdmin consumingPartyAdmin;
  private final ApiKeyService apiKeyService;

  ConsumingPartyKeyController(
      ConsumingPartyAdmin consumingPartyAdmin, ApiKeyService apiKeyService) {
    this.consumingPartyAdmin = consumingPartyAdmin;
    this.apiKeyService = apiKeyService;
  }

  @Operation(
      summary = "Mint an API key for a consuming party",
      description =
          "Creates a CONSUMING_PARTY-owned API key (scope: consume) for the given party. The"
              + " response's rawKey is shown exactly once — the platform stores only its SHA-256"
              + " hash and prefix (spec FS-0.6b §4). Revoke it later via POST"
              + " /api/v1/admin/api-keys/{id}/revoke. Requires the admin scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Key minted (rawKey shown once)"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the admin scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No party with this id (KH-CNS-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/api/v1/admin/consuming-parties/{id}/api-keys")
  ResponseEntity<CreateApiKeyResponse> mintKey(@PathVariable String id) {
    UUID partyId = UUID.fromString(id);
    consumingPartyAdmin.get(partyId); // 404 (KH-CNS-0404) if the party does not exist
    CreatedApiKey created =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, partyId, Set.of("consume"));
    return ResponseEntity.ok(
        new CreateApiKeyResponse(created.id(), created.keyPrefix(), created.rawKey()));
  }
}
