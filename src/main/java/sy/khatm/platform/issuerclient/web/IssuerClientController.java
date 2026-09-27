package sy.khatm.platform.issuerclient.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.issuerclient.domain.IssuerClientLifecycle;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Issuer-client administration for the caller's own tenant (KH-2.8.1, spec FS-2.7a §3.2).
 *
 * <p>Gated by {@code rbac.security.SecurityConfig} on the {@code key:manage} scope, console session
 * only — the key is signing-grade material, so this is deliberately narrower than {@code
 * tenant:admin}. A parent organisation reaches its children's clients through {@link
 * OrgIssuerClientController}, never through this controller.
 *
 * <p>Thin: validate → call {@link IssuerClientLifecycle} → map.
 */
@RestController
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/issuer-clients")
@Tag(
    name = "issuer-clients",
    description = "Machine-to-machine issuer clients — keys, rotation, suspension, revocation")
class IssuerClientController {

  private final IssuerClientLifecycle lifecycle;
  private final IssuerClientMapper mapper;

  IssuerClientController(IssuerClientLifecycle lifecycle, IssuerClientMapper mapper) {
    this.lifecycle = lifecycle;
    this.mapper = mapper;
  }

  @Operation(
      operationId = "listIssuerClients",
      summary = "List issuer clients",
      description =
          "The tenant's issuer clients, newest first: prefix, status, scopes, allowed schemas,"
              + " last use. Never returns the key, its secret, or its hash. Requires key:manage.",
      responses = {
        @ApiResponse(responseCode = "200", description = "The tenant's issuer clients"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid console session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the key:manage scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping
  List<IssuerClientResponse> list() {
    return lifecycle.list().stream().map(mapper::toResponse).toList();
  }

  @Operation(
      operationId = "createIssuerClient",
      summary = "Create an issuer client",
      description =
          "Creates a client and returns its khi_ API key ONCE — it cannot be retrieved again. For"
              + " the first client of a root tenant the response also carries the tenant's holder"
              + " HMAC secret (holderHmacSecret), also shown once; later clients and child-tenant"
              + " clients never carry one. Requires key:manage.",
      responses = {
        @ApiResponse(
            responseCode = "201",
            description = "Created; the one-time key is in the body"),
        @ApiResponse(
            responseCode = "400",
            description = "Bean Validation failed, or an unknown schema id (KH-ICL-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid console session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the key:manage scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "503",
            description = "The holder secret could not be stored in Vault (KH-ICL-0503)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  CreatedIssuerClientResponse create(@Valid @RequestBody CreateIssuerClientRequest req) {
    return mapper.toCreatedResponse(
        lifecycle.create(req.name().toLocalizedText(), req.allowedSchemaIds(), req.expiresAt()));
  }

  @Operation(
      operationId = "rotateIssuerClient",
      summary = "Rotate an issuer client's key",
      description =
          "Mints a replacement client (new key, same schemas and expiry) and puts this one into a"
              + " grace window: it keeps authenticating for retireAfterHours (0-72, default 24),"
              + " then is revoked by the worker. Only an ACTIVE client can be rotated. Requires"
              + " key:manage.",
      responses = {
        @ApiResponse(
            responseCode = "201",
            description = "Replacement created; new key in the body"),
        @ApiResponse(
            responseCode = "400",
            description = "retireAfterHours outside 0-72 (KH-ICL-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid console session",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the key:manage scope (KH-RBC-0403)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No such client in this tenant (KH-ICL-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "The client is not ACTIVE (KH-ICL-1409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/rotate")
  @ResponseStatus(HttpStatus.CREATED)
  CreatedIssuerClientResponse rotate(
      @PathVariable String id, @RequestBody(required = false) RotateIssuerClientRequest req) {
    Integer hours = req == null ? null : req.retireAfterHours();
    return mapper.toCreatedResponse(lifecycle.rotate(UUID.fromString(id), hours));
  }

  @Operation(
      operationId = "suspendIssuerClient",
      summary = "Suspend an issuer client",
      description = "ACTIVE → SUSPENDED; its key is rejected until resumed. Requires key:manage.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Suspended"),
        @ApiResponse(
            responseCode = "404",
            description = "No such client (KH-ICL-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "The client is not ACTIVE (KH-ICL-1409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/suspend")
  IssuerClientResponse suspend(@PathVariable String id) {
    return mapper.toResponse(lifecycle.suspend(UUID.fromString(id)));
  }

  @Operation(
      operationId = "resumeIssuerClient",
      summary = "Resume an issuer client",
      description = "SUSPENDED → ACTIVE. Requires key:manage.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Resumed"),
        @ApiResponse(
            responseCode = "404",
            description = "No such client (KH-ICL-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "The client is not SUSPENDED (KH-ICL-1409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/resume")
  IssuerClientResponse resume(@PathVariable String id) {
    return mapper.toResponse(lifecycle.resume(UUID.fromString(id)));
  }

  @Operation(
      operationId = "revokeIssuerClient",
      summary = "Revoke an issuer client",
      description = "Permanent. Requires key:manage.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Revoked"),
        @ApiResponse(
            responseCode = "404",
            description = "No such client (KH-ICL-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "Already revoked (KH-ICL-1409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/revoke")
  IssuerClientResponse revoke(@PathVariable String id) {
    return mapper.toResponse(lifecycle.revoke(UUID.fromString(id)));
  }
}
