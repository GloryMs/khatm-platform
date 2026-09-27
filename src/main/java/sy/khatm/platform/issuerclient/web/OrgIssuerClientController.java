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
import sy.khatm.platform.issuerclient.domain.OrgIssuerClientService;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * A parent organisation's view of its direct children's issuer clients (KH-2.8.1, spec FS-2.7a D10)
 * under {@code /api/v1/org/children/{id}/issuer-clients} — the same path family as {@code
 * rbac.web.OrgAdminController}, keyed by the child tenant's id exactly like every other route in it
 * (spec text says {@code {slug}}; consistency with the live plane won). Gated by the {@code
 * /api/v1/org/**} rule ({@code org:admin}, console session). Every child-resolution failure is the
 * unified {@code KH-ORG-0404}.
 */
@RestController
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/org/children/{childId}/issuer-clients")
@Tag(
    name = "org-issuer-clients",
    description = "Parent-organisation management of direct children's issuer clients")
class OrgIssuerClientController {

  private final OrgIssuerClientService service;
  private final IssuerClientMapper mapper;

  OrgIssuerClientController(OrgIssuerClientService service, IssuerClientMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Operation(
      operationId = "listChildIssuerClients",
      summary = "List a child tenant's issuer clients",
      description = "Requires org:admin on the parent tenant; the child must be a direct child.",
      responses = {
        @ApiResponse(responseCode = "200", description = "The child's issuer clients"),
        @ApiResponse(
            responseCode = "404",
            description = "No such direct child (KH-ORG-0404, unified)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping
  List<IssuerClientResponse> list(@PathVariable String childId) {
    return service.list(UUID.fromString(childId)).stream().map(mapper::toResponse).toList();
  }

  @Operation(
      operationId = "createChildIssuerClient",
      summary = "Create an issuer client in a child tenant",
      description =
          "As POST /api/v1/issuer-clients, in the child. The child inherits the root tenant's"
              + " holder secret, so no holderHmacSecret is ever returned here.",
      responses = {
        @ApiResponse(
            responseCode = "201",
            description = "Created; the one-time key is in the body"),
        @ApiResponse(
            responseCode = "404",
            description = "No such direct child (KH-ORG-0404, unified)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  CreatedIssuerClientResponse create(
      @PathVariable String childId, @Valid @RequestBody CreateIssuerClientRequest req) {
    return mapper.toCreatedResponse(
        service.create(
            UUID.fromString(childId),
            req.name().toLocalizedText(),
            req.allowedSchemaIds(),
            req.expiresAt()));
  }

  @Operation(
      operationId = "rotateChildIssuerClient",
      summary = "Rotate a child's issuer client",
      responses = {
        @ApiResponse(responseCode = "201", description = "Replacement created"),
        @ApiResponse(
            responseCode = "404",
            description = "No such direct child (KH-ORG-0404) or client (KH-ICL-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{clientId}/rotate")
  @ResponseStatus(HttpStatus.CREATED)
  CreatedIssuerClientResponse rotate(
      @PathVariable String childId,
      @PathVariable String clientId,
      @RequestBody(required = false) RotateIssuerClientRequest req) {
    Integer hours = req == null ? null : req.retireAfterHours();
    return mapper.toCreatedResponse(
        service.rotate(UUID.fromString(childId), UUID.fromString(clientId), hours));
  }

  @Operation(
      operationId = "suspendChildIssuerClient",
      summary = "Suspend a child's issuer client",
      responses = {
        @ApiResponse(responseCode = "200", description = "Suspended"),
        @ApiResponse(
            responseCode = "404",
            description = "No such direct child or client",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{clientId}/suspend")
  IssuerClientResponse suspend(@PathVariable String childId, @PathVariable String clientId) {
    return mapper.toResponse(service.suspend(UUID.fromString(childId), UUID.fromString(clientId)));
  }

  @Operation(
      operationId = "resumeChildIssuerClient",
      summary = "Resume a child's issuer client",
      responses = {
        @ApiResponse(responseCode = "200", description = "Resumed"),
        @ApiResponse(
            responseCode = "404",
            description = "No such direct child or client",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{clientId}/resume")
  IssuerClientResponse resume(@PathVariable String childId, @PathVariable String clientId) {
    return mapper.toResponse(service.resume(UUID.fromString(childId), UUID.fromString(clientId)));
  }

  @Operation(
      operationId = "revokeChildIssuerClient",
      summary = "Revoke a child's issuer client",
      responses = {
        @ApiResponse(responseCode = "200", description = "Revoked"),
        @ApiResponse(
            responseCode = "404",
            description = "No such direct child or client",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{clientId}/revoke")
  IssuerClientResponse revoke(@PathVariable String childId, @PathVariable String clientId) {
    return mapper.toResponse(service.revoke(UUID.fromString(childId), UUID.fromString(clientId)));
  }
}
