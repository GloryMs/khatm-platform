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
