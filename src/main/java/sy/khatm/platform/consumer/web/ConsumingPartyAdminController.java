package sy.khatm.platform.consumer.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.consumer.api.ConsumingPartyAdmin;
import sy.khatm.platform.consumer.api.ConsumingPartyView;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Consuming-party admin plane (KH-1.4.4, {@code /api/v1/admin/consuming-parties}) — register
 * verifiers, flip their {@code ACTIVE}/{@code SUSPENDED} status, and manage their schema allowlist.
 *
 * <p>Guarded by the {@code consumer:manage} scope (spec FS-2.2 D2) — {@code
 * rbac.security.SecurityConfig}'s {@code ADMIN_CONSUMING_PARTIES_PATH} rule (session or {@code
 * TENANT} key holding {@code consumer:manage}; {@code CONSUMING_PARTY} keys are rejected here as
 * everywhere on the admin plane).
 *
 * <p>Consuming-party API-key minting ({@code POST /{id}/api-keys}) lives in {@code rbac.web}
 * instead — only the {@code rbac} module may create {@code api_key} rows, and having {@code
 * consumer} depend on {@code rbac} would introduce a module cycle ({@code rbac} already depends on
 * {@code consumer :: api}). Revocation reuses the existing {@code POST
 * /api/v1/admin/api-keys/{id}/revoke}.
 *
 * <p>Thin: validate → call {@link ConsumingPartyAdmin} → map. Module-private — Spring MVC discovers
 * it via component scan; no other module references this class.
 */
@RestController
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/admin/consuming-parties")
@Tag(
    name = "consuming-parties-admin",
    description = "Consuming-party (verifier) administration — registry, status, schema allowlist")
class ConsumingPartyAdminController {

  private final ConsumingPartyAdmin admin;

  ConsumingPartyAdminController(ConsumingPartyAdmin admin) {
    this.admin = admin;
  }

  @Operation(
      summary = "List consuming parties",
      description =
          "Every consuming party registered for the tenant (newest first), each with its status"
              + " and resolved schema allowlist. Requires the consumer:manage scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "The tenant's consuming parties"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the consumer:manage scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping
  List<ConsumingPartyView> list() {
    return admin.list();
  }

  @Operation(
      summary = "Register a consuming party",
      description =
          "Creates a party with the given code and bilingual name. The code is a lowercase slug"
              + " (^[a-z0-9][a-z0-9-_]{1,62}$); the row's id is derived deterministically from"
              + " (tenant, code), so this is idempotent by identity — but registering an"
              + " already-registered code is a conflict (KH-CNS-0409), not a silent overwrite."
              + " Requires the consumer:manage scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Party registered"),
        @ApiResponse(
            responseCode = "400",
            description = "Bean Validation failed, or an invalid code format (KH-CNS-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the consumer:manage scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "A party with this code already exists (KH-CNS-0409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping
  ConsumingPartyView create(@Valid @RequestBody CreateConsumingPartyRequest req) {
    return admin.create(req.code(), req.nameI18n().toLocalizedText());
  }

  @Operation(
      summary = "Suspend a consuming party",
      description =
          "Flips the party to SUSPENDED — its API keys immediately stop authenticating (KH-1.4.4"
              + " D4), the same outcome as a revoked key. Idempotent. Requires the consumer:manage"
              + " scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Party suspended"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the consumer:manage scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No party with this id (KH-CNS-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/suspend")
  ConsumingPartyView suspend(@PathVariable String id) {
    return admin.suspend(UUID.fromString(id));
  }

  @Operation(
      summary = "Reactivate a consuming party",
      description =
          "Flips a SUSPENDED party back to ACTIVE — its API keys authenticate again. Idempotent."
              + " Requires the consumer:manage scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Party activated"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the consumer:manage scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No party with this id (KH-CNS-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/activate")
  ConsumingPartyView activate(@PathVariable String id) {
    return admin.activate(UUID.fromString(id));
  }

  @Operation(
      summary = "Add a schema to a party's allowlist",
      description =
          "Scopes the party to consume credentials issued against the given schema (deny-by-"
              + "default: a party with an empty allowlist can consume nothing). Idempotent. The"
              + " schema must exist in the tenant (KH-CNS-1404 otherwise). Requires the admin"
              + " scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Schema allowed; updated party view"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the consumer:manage scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No such party (KH-CNS-0404) or schema (KH-CNS-1404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/allowed-schemas")
  ConsumingPartyView allowSchema(
      @PathVariable String id, @Valid @RequestBody AllowSchemaRequest req) {
    return admin.allowSchema(UUID.fromString(id), req.schemaId());
  }

  @Operation(
      summary = "Remove a schema from a party's allowlist",
      description =
          "Idempotent — removing a pair that is not allowed (including for an unknown party) is a"
              + " successful 204 no-op. Requires the consumer:manage scope.",
      responses = {
        @ApiResponse(responseCode = "204", description = "Removed, or nothing to remove"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the consumer:manage scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @DeleteMapping("/{id}/allowed-schemas/{schemaId}")
  ResponseEntity<Void> disallowSchema(@PathVariable String id, @PathVariable String schemaId) {
    admin.disallowSchema(UUID.fromString(id), UUID.fromString(schemaId));
    return ResponseEntity.noContent().build();
  }
}
