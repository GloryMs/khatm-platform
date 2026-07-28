package sy.khatm.platform.schema.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.schema.api.SchemaAuthoringRequest;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaCreateRequest;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.schema.api.SchemaSummary;
import sy.khatm.platform.schema.domain.SchemaAuthoringService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Credential schema lookups (KH-1.6-early) plus full authoring (KH-1.1.1): create a {@code DRAFT},
 * edit it, publish it, version it, or archive it.
 *
 * <p>Thin: validate → call {@link SchemaCatalog}/{@link SchemaAuthoringService} → return. Every
 * authoring endpoint here (everything but the two {@code GET}s) requires the {@code schema:manage}
 * scope; the two {@code GET}s require any of {@code rbac.security.ScopeRegistry#SCHEMA_READ_SCOPES}
 * (server-side gate — see {@code rbac.security.SecurityConfig}'s Javadoc, spec FS-2.2 D2/V2).
 *
 * <p>Module-private — Spring MVC discovers it via component scan; no other module references this
 * class.
 */
@RestController
@RequestMapping("/api/v1/schemas")
@Tag(name = "schema", description = "Credential schema (credential type) lookups and authoring")
class SchemaController {

  private final SchemaCatalog catalog;
  private final SchemaAuthoringService authoring;

  SchemaController(SchemaCatalog catalog, SchemaAuthoringService authoring) {
    this.catalog = catalog;
    this.authoring = authoring;
  }

  @Operation(
      summary = "List credential schemas",
      description =
          "Read-only tenant metadata (id, display name, version, status) — every authenticated"
              + " actor kind may call this, no specific scope required (a deliberate, documented"
              + " decision: see rbac.security.SecurityConfig's Javadoc). The optional status"
              + " filter (KH-1.1.1) lets the console's schema management view show DRAFT rows"
              + " too; the issue-form picker keeps filtering to PUBLISHED client-side, as"
              + " before.",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Every matching schema registered for the tenant"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping
  List<SchemaSummary> list(@RequestParam(required = false) String status) {
    return catalog.listAll(status);
  }

  @Operation(
      summary = "Fetch a credential schema's detail",
      description =
          "Adds the raw claims definition to the list view's fields, so a console issue screen can"
              + " render the schema's claim fields. Same access rule as the list endpoint —"
              + " authenticated, any scope.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Schema detail"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No schema with this id (KH-SCH-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping("/{id}")
  SchemaDetail get(@PathVariable String id) {
    return catalog
        .findDetailById(UUID.fromString(id))
        .orElseThrow(() -> new NotFoundException(ErrorCode.KH_SCH_0404, "schema.not-found", id));
  }

  @Operation(
      summary = "Create a new DRAFT credential schema (version 1)",
      description =
          "Requires the schema:manage scope. Server-side validation rejects an empty claimsDef, a"
              + " claim field with an unsupported type (text/number/date), a nameI18n or claim"
              + " labelI18n missing en or ar, an sdFields entry not among the claim field names, or"
              + " a code already registered at version 1 — all as KH-SCH-0400. The schema starts"
              + " DRAFT and unavailable for issuance until POST /{id}/publish.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Schema created (DRAFT)"),
        @ApiResponse(
            responseCode = "400",
            description = "Bean Validation failed, or a business-level check (KH-SCH-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the schema:manage scope",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping
  SchemaDetail create(@Valid @RequestBody SchemaCreateRequest req) {
    return authoring.create(req);
  }

  @Operation(
      summary = "Rewrite a DRAFT schema's authoring fields in place",
      description =
          "Requires the schema:manage scope. DRAFT only — fixes mistakes before publish. Validated"
              + " identically to POST /api/v1/schemas.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Schema updated"),
        @ApiResponse(
            responseCode = "400",
            description = "Bean Validation failed, or a business-level check (KH-SCH-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the schema:manage scope",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No schema with this id (KH-SCH-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "The schema is not DRAFT (KH-SCH-0409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PutMapping("/{id}")
  SchemaDetail update(@PathVariable String id, @Valid @RequestBody SchemaAuthoringRequest req) {
    return authoring.update(UUID.fromString(id), req);
  }

  @Operation(
      summary = "Publish a DRAFT schema",
      description =
          "Requires the schema:manage scope. DRAFT -> PUBLISHED — the immutability line: a"
              + " published schema's claim fields can never be mutated again (no general update"
              + " endpoint exists for a PUBLISHED schema); a mistake found later needs a new"
              + " version (POST /{id}/versions) instead. A PUBLISHED schema becomes a valid"
              + " issuance target for POST /api/v1/credentials/issue's schemaCode.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Schema published"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the schema:manage scope",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No schema with this id (KH-SCH-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "The schema is not DRAFT (KH-SCH-1409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/publish")
  SchemaDetail publish(@PathVariable String id) {
    return authoring.publish(UUID.fromString(id));
  }

  @Operation(
      summary = "Create a new DRAFT version of a PUBLISHED schema",
      description =
          "Requires the schema:manage scope. Same code, version + 1. The console is responsible for"
              + " prefilling the request body from the source schema's current fields — this"
              + " endpoint validates the submitted body exactly like POST /api/v1/schemas, with"
              + " no server-side default-merging.",
      responses = {
        @ApiResponse(responseCode = "200", description = "New DRAFT version created"),
        @ApiResponse(
            responseCode = "400",
            description = "Bean Validation failed, or a business-level check (KH-SCH-0400)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the schema:manage scope",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No schema with this id (KH-SCH-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "The source schema is not PUBLISHED (KH-SCH-1409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/versions")
  SchemaDetail createVersion(
      @PathVariable String id, @Valid @RequestBody SchemaAuthoringRequest req) {
    return authoring.createVersion(UUID.fromString(id), req);
  }

  @Operation(
      summary = "Archive a PUBLISHED schema",
      description =
          "Requires the schema:manage scope. PUBLISHED -> ARCHIVED — stops NEW issuance against"
              + " this schema (POST /api/v1/credentials/issue and the internal find-or-create path"
              + " both reject it, KH-SCH-1409); every credential already issued against it, and its"
              + " verification/consumption, is completely unaffected.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Schema archived"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "403",
            description = "Missing the schema:manage scope",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No schema with this id (KH-SCH-0404)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @ApiResponse(
            responseCode = "409",
            description = "The schema is not PUBLISHED (KH-SCH-1409)",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @PostMapping("/{id}/archive")
  SchemaDetail archive(@PathVariable String id) {
    return authoring.archive(UUID.fromString(id));
  }
}
