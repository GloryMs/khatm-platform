package sy.khatm.platform.schema.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI for resolving credential schemas by code/version or id.
 *
 * <p>This is the only cross-module surface of the {@code schema} module. Other modules that need to
 * issue against a schema depend on this interface, never on the {@code schema} module's internal
 * entities.
 */
public interface SchemaCatalog {

  /**
   * Return the schema matching {@code definition.code()} and {@code definition.version()} for the
   * current tenant, creating and publishing it first if it does not yet exist.
   *
   * <p>Real schema authoring is a console-driven workflow (KH-1.x); this find-or-create path exists
   * so that callers such as the demo seeder can obtain a valid {@code schema_id} without that
   * workflow being built yet.
   *
   * @param definition the schema to find or create; must not be {@code null}
   * @return an opaque reference to the (possibly newly created) schema
   */
  SchemaRef ensurePublished(SchemaDefinition definition);

  /**
   * Look up a schema by its internal id, e.g. to display {@code code} for a credential that
   * references it.
   *
   * @param id the schema's internal UUID; must not be {@code null}
   * @return the matching schema reference, or empty if no such schema exists
   */
  Optional<SchemaRef> findById(UUID id);

  /**
   * List every schema for the current tenant, list-view shape (KH-1.6-early, {@code GET
   * /api/v1/schemas}).
   *
   * <p>Read-only tenant metadata; full schema authoring/versioning stays KH-1.1's backend half.
   *
   * @return every schema registered for the current tenant
   */
  List<SchemaSummary> listAll();

  /**
   * Look up a schema by its internal id, detail-view shape — {@link SchemaSummary}'s fields plus
   * the claims definition a console issue form needs (KH-1.6-early, {@code GET
   * /api/v1/schemas/{id}}).
   *
   * @param id the schema's internal UUID; must not be {@code null}
   * @return the matching schema detail, or empty if no such schema exists
   */
  Optional<SchemaDetail> findDetailById(UUID id);
}
