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
   * Look up a schema by its machine {@code code} at version {@code 1}, for the current tenant
   * (KH-2.4, spec FS-2.4 item 2's bulk-issuance pre-check) — the same {@code (code, version=1)}
   * resolution {@link #ensurePublished} and the quick-issue path already use exclusively; a schema
   * authored at a later version is never reachable through this lookup, matching that existing
   * limitation.
   *
   * @param code the schema's machine code; must not be {@code null}
   * @return the matching schema reference, or empty if no version-1 schema with this code exists
   */
  Optional<SchemaRef> findByCode(String code);

  /**
   * Resolve a schema by its internal id for issuance, requiring it be {@code PUBLISHED} — the exact
   * validation {@link #ensurePublished} already applies to a resolved existing row, exposed for
   * callers that already know precisely which schema version they mean (KH-2.4-BE follow-up: {@code
   * CredentialService#issue} previously had no way to honor a specific version the console had the
   * operator select, and always fell back to {@code (code, version=1)} via {@link
   * #ensurePublished}/{@link #findByCode} — see those two methods' Javadoc for that limitation).
   *
   * @param id the schema's internal UUID; must not be {@code null}
   * @return the matching schema reference
   * @throws sy.khatm.platform.shared.error.NotFoundException if no schema with this id exists
   * @throws sy.khatm.platform.shared.error.ConflictException if the schema is not {@code PUBLISHED}
   */
  SchemaRef requirePublishedById(UUID id);

  /**
   * List schemas for the current tenant, list-view shape (KH-1.6-early, {@code GET
   * /api/v1/schemas}), optionally filtered by lifecycle status (KH-1.1.1 — the console's schema
   * management view needs to see {@code DRAFT} rows too, unlike the issue-form picker, which keeps
   * filtering to {@code PUBLISHED} client-side as before).
   *
   * @param status one of {@code DRAFT}/{@code PUBLISHED}/{@code DEPRECATED}/{@code ARCHIVED}, or
   *     {@code null} to return every status
   * @return every matching schema registered for the current tenant
   */
  List<SchemaSummary> listAll(String status);

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
