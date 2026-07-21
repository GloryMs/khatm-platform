/**
 * Schema module — credential schema registry.
 *
 * <p><b>Responsibilities:</b> define and version credential schemas (e.g. {@code
 * CriminalRecordExtract} v1), enforce claim constraints, publish schema metadata to verifiers.
 *
 * <p><b>Exposed API:</b> {@code api/} sub-package — {@link
 * sy.khatm.platform.schema.api.SchemaCatalog#ensurePublished} finds or creates a schema by
 * code/version (KH-0.2.1, so callers such as the demo seeder have a valid {@code schema_id} without
 * going through authoring; KH-1.1.1 made it reject a resolved existing row that is {@code DRAFT} or
 * {@code ARCHIVED} rather than silently returning it, now that those statuses are reachable through
 * real authoring). {@code #listAll}/{@code #findDetailById} (KH-1.6-early, {@code listAll} gaining
 * an optional status filter at KH-1.1.1) back {@code GET /api/v1/schemas}/{@code /{id}}.
 *
 * <p><b>Authoring (KH-1.1.1, module-private — {@code schema.domain.SchemaAuthoringService}):</b>
 * {@code POST /api/v1/schemas} (create {@code DRAFT} v1), {@code PUT /api/v1/schemas/{id}} ({@code
 * DRAFT}-only in-place edit), {@code POST /api/v1/schemas/{id}/publish} ({@code DRAFT} → {@code
 * PUBLISHED} — the immutability line, no general update endpoint exists for a published schema),
 * {@code POST /api/v1/schemas/{id}/versions} (new {@code DRAFT} version of a {@code PUBLISHED}
 * schema), {@code POST /api/v1/schemas/{id}/archive} ({@code PUBLISHED} → {@code ARCHIVED}, stops
 * new issuance only — existing credentials/verification unaffected). All five require the {@code
 * admin} scope (a real {@code schema:manage} permission waits for KH-2.2).
 *
 * <p><b>Published events:</b> (none yet)
 *
 * <p><b>Tables owned:</b> {@code credential_schema}
 *
 * <p><b>Status:</b> full CRUD/lifecycle authoring (KH-1.1.1) plus the read-only list/detail REST
 * endpoints (KH-1.6-early) and the find-or-create API (KH-0.2.1) other modules issue against.
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.schema;
