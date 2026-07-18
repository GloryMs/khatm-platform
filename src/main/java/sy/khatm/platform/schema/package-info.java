/**
 * Schema module — credential schema registry.
 *
 * <p><b>Responsibilities:</b> define and version credential schemas (e.g. {@code
 * CriminalRecordExtract} v1), enforce claim constraints, publish schema metadata to verifiers.
 *
 * <p><b>Exposed API:</b> {@code api/} sub-package — {@link
 * sy.khatm.platform.schema.api.SchemaCatalog#ensurePublished} finds or creates a schema by
 * code/version (KH-0.2.1, so callers such as the demo seeder have a valid {@code schema_id} before
 * the console-driven authoring workflow exists). {@code #listAll}/{@code #findDetailById}
 * (KH-1.6-early) back the read-only {@code GET /api/v1/schemas}/{@code /{id}} endpoints. Full
 * schema authoring/versioning UI is KH-1.x.
 *
 * <p><b>Published events:</b> (none yet)
 *
 * <p><b>Tables owned:</b> {@code credential_schema}
 *
 * <p><b>Status:</b> minimal persistence + find-or-create API (KH-0.2.1); read-only list/detail REST
 * endpoints (KH-1.6-early). Authoring workflow, claim validation, versioning rules deferred to
 * KH-1.x.
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.schema;
