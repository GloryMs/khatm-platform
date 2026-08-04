# Session brief — KH-1.1-BE: schema management + credential search + idempotency race (khatm-platform)

Three-part support-mode session driven by console C2's needs plus one flagged debt.
This brief is the spec (decisions pre-approved).

## Before writing anything

1. Read: `CLAUDE.md`, `docs/STATE.md` (esp. "Next up" #3 — the KH-1.4.1/1.4.2 race
   analysis; Part C implements exactly the ruling recorded there), `docs/CONVENTIONS.md`
   §7, the `schema` module (`SchemaCatalog`, entity, the `status` values and any CHECK
   constraint on `credential_schema.status` — verify what statuses V1 actually allows
   before designing transitions), `CredentialService#consume` + the Redis fast-path,
   `consumption_event` repository, `docs/api/openapi.json`.
2. Confirm `main` includes PR #24. If not, STOP and report.
3. Branch: `feat/KH-1.1-BE-schema-mgmt-and-search`.

## Part A — Schema management endpoints (KH-1.1.1 backend half)

- All under `RequireScope`-equivalent server-side gate: **`admin` scope** (decision:
  `schema:manage` waits for KH-2.2 full RBAC; no role-seed migration now).
- Endpoints (exact DTO field names are the session's call; annotate fully — the
  console types from these):
  - `POST /api/v1/schemas` — create DRAFT: `code`, `nameI18n` (en+ar both required —
    the `name_i18n` CHECK exists), `claimsDef`, `sdFields`, `defaultMaxUses`,
    `defaultValidity`. Server-side validation: field names sane; types limited to
    the supported set (text/number/date — whatever `claims_def` parsing already
    honors); every claim's `label_i18n` has BOTH en and ar (work rule 2 enforced at
    the data layer, reject otherwise); `sdFields` ⊆ claim names; version starts at 1.
  - `POST /api/v1/schemas/{id}/publish` — DRAFT→PUBLISHED. Publishing is the
    immutability line: reject any mutation of a PUBLISHED schema's `claimsDef`/
    `sdFields` (there is deliberately NO general update endpoint for published ones).
  - `PUT /api/v1/schemas/{id}` — DRAFT only (fix mistakes before publishing);
    PUBLISHED/ARCHIVED → 409-style domain error.
  - `POST /api/v1/schemas/{id}/versions` — new DRAFT version of a PUBLISHED schema:
    same `code`, version+1, body may override any authoring field (prefill-from-prior
    is the console's job; the server just validates like create).
  - `POST /api/v1/schemas/{id}/archive` — PUBLISHED→ARCHIVED (stops NEW issuance;
    existing credentials/verification unaffected — say so in the annotation).
  - If the existing status CHECK constraint lacks ARCHIVED (verify first): additive
    `V4` migration altering the constraint is authorized — nothing else in it.
- The existing read endpoints stay as-is for operators; the LIST endpoint gains an
  optional `status` filter (default: all) so the console's management view can show
  drafts — the issue picker keeps filtering PUBLISHED client-side as today.
- Issuance guard: `ensurePublished` already exists — confirm issue rejects
  DRAFT/ARCHIVED schemas with a proper domain error (test it if untested).
- Audit: `SCHEMA_CREATED`, `SCHEMA_UPDATED` (draft edits), `SCHEMA_PUBLISHED`,
  `SCHEMA_VERSION_CREATED`, `SCHEMA_ARCHIVED` — entity_ref = code/version, no
  claims_def dump in detail.
- New error codes in the schema range for: validation failure, immutable-after-publish,
  invalid transition — both bundles, Arabic gate at merge, error-codes.md regenerated.

## Part B — Credential search/list (KH-1.1.4 backend half)

- `GET /api/v1/credentials` — session-authenticated (any operator), tenant-scoped,
  paged (`page`/`size`, size cap 100, sort `issuedAt` desc). Filters, all optional,
  AND-combined: `ref` (exact), `pseudoRef` (exact), `schemaId`, `revoked` (bool).
- Response: page envelope + `CredentialSummary` rows reusing the existing summary
  shape (id, ref, schema name/code, issuedAt, validity, uses, revoked/derived
  status) — the console's Revoke page already renders this shape.
- Performance: one paged query; verify indexes cover the filters (V1 indexed
  `tenant_id` + ref/pseudo_ref lookups — check; if a filter would table-scan, add
  the index in the same V4 migration and say so).
- No claims content anywhere in list rows (proofs not content — summaries only).

## Part C — Close the idempotency race (KH-1.4.1/1.4.2 ruling)

- In `CredentialService#consume`: catch the unique-violation on
  `consumption_event.idempotency_key` insert; on catch, load the existing event by
  key and return the winner's recorded outcome as a normal (idempotent) response —
  indistinguishable from the Redis fast-path hit. No 500.
- Concurrency test targeting THIS race specifically: two real threads, same
  idempotencyKey, Redis fast-path guaranteed cold (flush/bypass), both hit the DB
  path → both callers receive the same successful outcome, exactly one
  `consumption_event` row, exactly one uses_remaining decrement. (The existing
  `ConcurrentConsumeTest` covers double-spend; this covers double-submit.)
- Update STATE's "Next up" #3 entry to CLOSED with this session's ref.

## Hard constraints

- Migrations: at most one additive `V4` (status CHECK widening and/or indexes) —
  nothing else in it; none at all if unneeded.
- No pom changes. Work rules 1–4 + CONVENTIONS §7 (incl. audit-outside-the-throwing-
  transaction lesson from KH-1.4.3 — schema-management denials/validations follow
  the same pattern where audit-then-throw applies).
- `/consume`, `/verify`, redeem hot paths: Part C only touches the failure branch;
  no added latency on the happy path.

## Exit protocol

- `mvn verify` green; full CI green (contract freshness — additive).
- PR against `main`. **Do NOT merge.**
- `docs/STATE.md`: session entry; Part C closure note; "Next up" → 1. C2 (console,
  other repo) 2. KH-1.1.3-BE bulk issuance endpoint when C2's wizard needs it
  3. KH-0.3.3 activation (Majd).
- Final message: PR link, test count, the new endpoints table, new error codes,
  whether V4 was needed and why, and the Part C test's name + what it proves.
