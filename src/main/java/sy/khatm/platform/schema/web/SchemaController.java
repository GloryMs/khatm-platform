package sy.khatm.platform.schema.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.schema.api.SchemaSummary;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Read-only credential schema lookups (KH-1.6-early, console issue-screen dependency).
 *
 * <p>Thin: validate → call {@link SchemaCatalog} → return. Full schema authoring (create/update/
 * delete, versioning rules) is KH-1.1's backend half, explicitly out of scope here — this
 * controller exposes only what the console's issue screen needs to list schemas and render an issue
 * form's claim fields.
 *
 * <p>Module-private — Spring MVC discovers it via component scan; no other module references this
 * class.
 */
@RestController
@RequestMapping("/api/v1/schemas")
@Tag(name = "schema", description = "Read-only credential schema (credential type) lookups")
class SchemaController {

  private final SchemaCatalog catalog;

  SchemaController(SchemaCatalog catalog) {
    this.catalog = catalog;
  }

  @Operation(
      summary = "List credential schemas",
      description =
          "Read-only tenant metadata (id, display name, version, status) — every authenticated"
              + " actor kind may call this, no specific scope required (a deliberate, documented"
              + " decision: see rbac.security.SecurityConfig's Javadoc). Full schema authoring is"
              + " KH-1.1's backend half.",
      responses = {
        @ApiResponse(responseCode = "200", description = "Every schema registered for the tenant"),
        @ApiResponse(
            responseCode = "401",
            description = "No valid session or API key",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
      })
  @GetMapping
  List<SchemaSummary> list() {
    return catalog.listAll();
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
}
