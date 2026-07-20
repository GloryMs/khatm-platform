# schema

Credential type registry — versioned definitions like `CriminalRecordExtract` v1: display
name, claim fields, selective-disclosure fields, default validity/max-uses.

**Events in:** none. **Events out:** none yet.

**Tables owned:** `credential_schema`.

**Status:** KH-0.2.1 adds persistence plus one cross-module method,
`SchemaCatalog#ensurePublished`, which finds or creates a schema by code/version so callers
(e.g. the demo seeder, `credential.CredentialService#issue`) have a valid `schema_id` before
the console-driven authoring workflow exists. `SchemaCatalog#findById` resolves a schema back
to its display code for read paths. Full authoring UI, claim validation, and versioning rules
are KH-1.x.

KH-0.4: `SchemaRef` now also carries `claimsDefJson` and `sdFields` (previously id/code/version
only) — `credential.CredentialService#verify`'s mandatory-disclosure check (spec FS-0.4 D2)
needs the full field list and the redefined `sd_fields` ("withholdable," not "hidden") to
decide which claims_def fields a presentation must always disclose. No new cross-module
boundary: this just widens the existing `schema :: api` DTO `credential` already depends on.

KH-1.6-early: `web/SchemaController` adds `GET /api/v1/schemas` (list: id, name_i18n, version,
status) and `GET /api/v1/schemas/{id}` (adds claims_def) — the console issue screen's read
dependency. Authenticated session OR any valid API key, no specific scope (read-only tenant
metadata; see `rbac.security.SecurityConfig`'s Javadoc for the explicit decision). Backed by two
new `SchemaCatalog` methods, `#listAll`/`#findDetailById`, and two new list/detail-view DTOs,
`SchemaSummary`/`SchemaDetail` — `SchemaRef` itself is unchanged (still what `credential` depends
on). New `KH-SCH-0404` error code for the first schema lookup that can actually fail.

KH-1.4.3 (schema response enrichment): `SchemaSummary` gains `code` — the value `POST
/api/v1/credentials/issue`'s `schemaCode` field expects, closing a console issue-screen contract
gap (the console had no way to discover valid `schemaCode` values). `SchemaDetail` gains `code`,
`sdFields`, `defaultMaxUses` (both already stored, just not surfaced), and `defaultValidity` — an
ISO-8601 duration string (e.g. `P90D`) read from the `default_validity` Postgres `interval` column
via a scalar `EXTRACT(epoch FROM ...)` native query (`CredentialSchemaRepository
#findDefaultValiditySeconds`) rather than mapping `interval` into any JPA/Hibernate Java type
directly — `null` if the schema has no configured default. Additive-only; `SchemaRef` (what
`credential` actually depends on for issuance) is unchanged.
