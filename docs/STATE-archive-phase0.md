# Archive date:2026-07-22
# Prev Taks:
- Prev task: **KH-1.4.3-and-schema-contract** (session `feat/KH-1.4.3-and-schema-contract`,
  2026-07-20) — the session that completed platform v1: auth (KH-0.6b), claim delivery + minting
  (KH-1.2.1/1.2.2), signed status list (KH-1.3), and consumption hardening (KH-1.4.3) are all real;
  the published contract (`docs/api/openapi.json`) is versioned and additive-only since
  KH-1.6-early. `mvn verify` green, 146/146 tests (4 new, up from 142). DONE & MERGED via PR #24
  (2026-07-20, merge commit `2b196d6`); branch `feat/KH-1.4.3-and-schema-contract` deleted.
  **Corrects a stale claim this file carried** ("PR open, not yet merged" — written mid-session,
  confirmed merged at the start of the KH-1.1-BE session via `git log` directly, per protocol).
  See "Last completed" → Session KH-1.4.3-and-schema-contract for the full two-part breakdown.
- Prev task: **KH-1.3** — signed status list (`GET /sl/{tenantSlug}/{listCode}`), spec FS-1.3 D1–D7
  pre-approved. `mvn verify` green, 142/142 tests (18 new). DONE & MERGED via PR #23 (2026-07-20,
  merge commit `9220780`); branch `feat/KH-1.3-status-list` deleted. **Corrects a stale claim this
  file carried** ("PR open, not yet merged" — written before merge, never updated after; caught at
  the start of the KH-1.4.3 session by checking `git log` directly per this file's now-repeated
  own lesson, same as the KH-1.2.2/KH-1.6-early sessions before it). **Completes the `status`
  module** — issuance-time bit allocation (KH-0.2.1), revoke-time bit flip + publish, and the
  public artifact endpoint are all real; nothing about status lists is a placeholder anymore.
- Prev task: **KH-1.2.2** — expose claim-code minting (`POST
  /api/v1/credentials/{id}/claim-code`), spec FS-1.2.1 D2's "issuer re-issues a claim code"
  recovery path realized over HTTP (no separate spec doc — the session brief itself was the spec,
  same precedent as KH-1.6-early). `mvn verify` green, 124/124 tests (12 new). DONE & MERGED via
  PR #22 (2026-07-19, merge commit `871c51b`); branch `feat/KH-1.2.2-claim-code-endpoint` deleted.
  **Corrects a stale claim this file carried** ("PR open, not yet merged" — written mid-session,
  never updated after the merge happened later the same session; caught at the start of the KH-1.3
  session by checking `git log` directly instead of trusting this file blindly — the third time
  this exact lesson has been flagged, after KH-1.6-early and the KH-1.2.2 session's own note about
  KH-1.2.1). See "Last completed" → Session KH-1.2.2 for the full breakdown.
- Prev task: **KH-1.2.1** — claim delivery (`POST /api/v1/claims/redeem`), spec FS-1.2.1, D1–D8
  pre-approved. `mvn verify` green, 112/112 tests (14 new). DONE & MERGED via PR #21 (2026-07-19,
  merge commit `165b022`); branch `feat/KH-1.2.1-claim-delivery` deleted. See "Last completed" →
  Session KH-1.2.1 for the full breakdown. **Closes the `disclosures_enc` blocker for good** — see
  "Open decisions / blockers" below, now empty of it.
- Prev task: **KH-1.6-early** — `/api/v1` path migration (the platform's one breaking contract
  change) + full OpenAPI annotation coverage + published, freshness-gated `docs/api/openapi.json`
  + the two read-only schema endpoints the console's issue screen needs. `mvn verify` green, 95/95
  tests. DONE & MERGED via PR #19 (2026-07-18, merge commit `652aa73`, fast-forward — `main` had
  not diverged); branch `feat/KH-1.6-early-api-v1-contract` deleted. See "Last completed" →
  Session KH-1.6-early for the four parts, the endpoint mapping table, and the pre-existing
  `ErrorEnvelopeTestSupport` bug found and fixed along the way.
- **The API contract is PUBLISHED/ADDITIVE-ONLY as of PR #19's merge**: `docs/api/openapi.json`,
  raw URL `https://raw.githubusercontent.com/GloryMs/khatm-platform/main/docs/api/openapi.json`
  — live now. A path rename/removal from here needs its own ADR; new endpoints/fields are always
  safe to add. `OpenApiContractTest` fails the build if the committed file ever drifts from what
  the code actually serves.
- Most recent session (chore, not a WBS task): **chore/state-update-post-pr19** — the planned
  STATE.md merge-record follow-up (matches the PR #16 → PR #17 pattern) turned up two more real
  bugs via its own CI run, both found and fixed in the same PR: (1) `rbac.domain.AdminBootstrap`
  had the identical unguarded `api`/`worker` concurrent-boot race `key.domain.KeyBootstrap` was
  fixed for at KH-0.3-closure, just not caught at the same time — a `compose-smoke` CI failure
  (duplicate-key violation on `app_user`) surfaced it for real; fixed with the same
  `khatm.web.enabled` gate. (2) `OpenApiContractTest`'s freshness-gate comparison was silently
  platform-dependent — Jackson's default pretty printer uses `System.lineSeparator()` (`\r\n` on
  Windows), so a locally regenerated contract could never byte-match the git-normalized (`eol=lf`)
  committed file on a Windows dev machine, independent of any real content drift; fixed by forcing
  `\n` in the object indenter only (array formatting left at Jackson's default so the committed
  file's inline-array style didn't change). Both fixes verified: `mvn verify` green (98/98), and
  `scripts/smoke.sh` re-run locally end-to-end (both boot phases) against the `AdminBootstrap` fix
  specifically, not just inferred from CI. See "Last completed" for detail.
- Prev task: **KH-0.3 Phase-0 closure** — DevOps gates + one docs promotion, **no application
  code** (the only `pom.xml` edits are Trivy-driven patch-level dependency bumps). DONE & MERGED
  via PR #16 (2026-07-18, merge commit `b7b5342`, fast-forward — `main` had not diverged); branch
  `chore/KH-0.3-phase0-closure` deleted. **PR #16's CI was fully green** (`verify`/`trivy`/
  `gitleaks`/`compose-smoke` all passed) before merge, per user confirmation. See "Last completed"
  → Session KH-0.3-closure for the five parts, the build-infra fix it forced, and the three
  CI-config bugs found and fixed via the PR's own CI runs.
- Before that: **KH-0.6b** — console auth, API keys, RBAC-lite & the full `audit_log` write path
  (spec FS-0.6b, D1–D10 as given; `mvn verify` green, 81/81 tests). DONE & MERGED via PR #14
  (2026-07-17, merge commit `e05008c`); branch `feat/KH-0.6b-auth` deleted. **Completes the
  application half of Phase 0** — no endpoint ships without authentication behind it from here.
  See "Decisions made" → Session KH-0.6b.
- Before that: ADR-09-WORKER — async worker skeleton (Spring Modulith externalized events →
  transactional outbox → Redis Streams) + first real worker (claim_code `disclosures_enc`
  expiry-zeroing, closing the remaining half of that blocker per FS-0.2 §3.7). DONE & MERGED via
  PR #12 (2026-07-16, merge commit `cad404e`); branch `feat/ADR-09-worker-skeleton` deleted.
  **ADR-09's worker architecture is now REAL, not aspirational**; the `disclosures_enc` blocker
  was reduced to on-claim zeroing only (folds into KH-1.2.1). See "Decisions made" → Session
  ADR-09-worker.
- Before that: KH-0.6a (error hierarchy & bilingual messages — CLAUDE.md work rules 2 & 3) —
  DONE & MERGED via PR #10 (2026-07-16). **Work rules 2 & 3 are now LIVE** — standing obligations
  promoted to `docs/CONVENTIONS.md §7` (the in-file "Immediate note" blocks were retired).
- Arabic-speaker review gate (FS-0.6a §4): ran for KH-0.6a (one wording refinement on
  `verify.reason.bad_sd_alg`), again for KH-0.6b's new `error.rbc.*` keys in the PR #14 merge
  session, and again for KH-1.6-early's new `schema.not-found` key (confirmed by the user directly
  before PR #19's merge, no wording changes) — no concerns raised in any of the three,
  `MessageBundleParityTest` stayed green throughout.
- PR #19 (`feat/KH-1.6-early-api-v1-contract` → `main`) merged 2026-07-18 (merge commit `652aa73`,
  fast-forward); branch deleted.
- PR #18 (`chore/swagger-and-flagged-fixes` → `main`) merged 2026-07-18 (merge commit `98cb234`);
  branch deleted. **Corrects a stale claim this file carried** ("PR #18 open, not yet merged" —
  written before merge, never updated after; caught at the start of the KH-1.6-early session by
  checking actual `git log`/`gh pr view` instead of trusting this file blindly).
- PR #16 (`chore/KH-0.3-phase0-closure` → `main`) merged 2026-07-18 (merge commit `b7b5342`,
  fast-forward); branch deleted.
- PR #14 (`feat/KH-0.6b-auth` → `main`) merged 2026-07-17 (merge commit `e05008c`); branch deleted.
- PR #10 (`feat/KH-0.6a-errors-i18n` → `main`) merged 2026-07-16 (merge commit `ec20f95`);
  branch deleted.
- PR #8 (`feat/KH-0.4-sdjwt-upgrade` → `main`) merged 2026-07-16; branch deleted.
- PR #6/#7 (docs ratifications + STATE.md follow-up) merged 2026-07-15; branches deleted.
- PR #5 (`feat/KH-0.5-key-provider-spi` → `main`) merged 2026-07-15; branch deleted.
- PR #4 (KH-0.3.1, CI pipeline) merged 2026-07-14 (commit `4a65a39`); branch deleted.
- Branch protection is enabled on this repo — all changes (including docs-only housekeeping)
  go through a PR, never a direct push to `main`.
- Prior session (chore, not a WBS task): **chore/swagger-and-flagged-fixes** — local/dev-only
  Swagger UI + the two flags KH-0.3-closure raised for "the next session touching
  `rbac.security`/`key.domain`" (CVE-2026-22732, `KeyBootstrap` race) + STATE hygiene. Merged via
  PR #18 (see above). CI green (`verify`/`trivy`/`gitleaks`/`compose-smoke`) before merge. See
  "Last completed" → Session chore/swagger-and-flagged-fixes for details.

  ## Last completed
  - 2026-07-21: KH-1.1-BE — schema management + credential search + idempotency race closure,
  three-part support-mode session (console C2's needs plus one flagged debt); brief itself was the
  spec, same precedent as KH-1.6-early/KH-1.2.2/KH-1.4.3. `mvn verify` green, 181/181 tests (35
  new, up from 146). PR open against `main`, **not merged** (session instruction). Branch
  `feat/KH-1.1-BE-schema-mgmt-and-search`. Confirmed `main` included PR #24 (KH-1.4.3) at session
  start via `git log` directly, per protocol.
  - **Part A — schema management (KH-1.1.1 backend half)**: full authoring lifecycle, all gated by
    the `admin` scope (`ScopeGuard.requireScope("admin")`, the same rule `/api/v1/admin/**` already
    used — `schema:manage` still waits for KH-2.2's full RBAC, no role-seed migration this
    session). New `schema.domain.SchemaAuthoringService` (module-private, `schema.web
    .SchemaController` in the same module calls it directly): `POST /api/v1/schemas` (create
    `DRAFT` v1), `PUT /api/v1/schemas/{id}` (`DRAFT`-only in-place edit — `PUBLISHED`/`ARCHIVED` →
    `KH-SCH-0409`), `POST /api/v1/schemas/{id}/publish` (`DRAFT` → `PUBLISHED`, the immutability
    line — no general update endpoint exists for a published schema), `POST
    /api/v1/schemas/{id}/versions` (new `DRAFT` version of a `PUBLISHED` source, same code,
    version + 1 — the console prefills the body from the source; the server validates it exactly
    like create, no server-side default-merging), `POST /api/v1/schemas/{id}/archive` (`PUBLISHED`
    → `ARCHIVED`, stops NEW issuance only — existing credentials/verification unaffected). Any
    other invalid lifecycle transition (double-publish, archiving a `DRAFT`, versioning a `DRAFT`)
    is `KH-SCH-1409`. Server-side authoring validation (new `KH-SCH-0400`, one code for every
    flavor, offending reason substituted via `{0}`): claim field types limited to `text`/`number`/
    `date`, every `nameI18n`/claim `labelI18n` requires non-blank `en` and `ar`, `claimsDef` must be
    non-empty with unique field names, `sdFields` must be a subset of the claim field names, `code`
    must not already be registered at version 1, `defaultMaxUses` must be `>= 1` if given,
    `defaultValidity` must be a valid ISO-8601 duration if given. `GET /api/v1/schemas` gained an
    optional `status` query filter (default: all — `SchemaCatalog#listAll` signature changed from
    no-arg to `listAll(String status)`; its one other caller, `rbac.seed.DemoApiKeySeeder`, updated
    to pass `null`) so the console's management view can show `DRAFT` rows; the issue-form picker
    keeps filtering to `PUBLISHED` client-side as before. New `AuditAction`s `SCHEMA_CREATED`/
    `SCHEMA_UPDATED`/`SCHEMA_PUBLISHED`/`SCHEMA_VERSION_CREATED`/`SCHEMA_ARCHIVED` — `entityRef` is
    always `code:version`, never a `claims_def` dump.
    - **Issuance guard (session brief's own explicit ask, previously untested):**
      `SchemaCatalogService#ensurePublished` (the find-or-create path `CredentialService#issue`
      calls) now rejects a resolved existing schema that is `DRAFT` or `ARCHIVED` — same
      `KH-SCH-1409` invalid-transition code — instead of silently issuing against it. This was a
      real, previously-unreachable gap: `ensurePublished`'s only callers before this session (the
      demo seeder) always created `PUBLISHED` rows directly, so a non-`PUBLISHED` row could never
      reach this method until real authoring (this session) made `DRAFT`/`ARCHIVED` reachable.
      `SchemaRef` (the `schema :: api` DTO `ensurePublished`/`findById` return) gained `nameI18n` —
      an additive field, needed by Part B's search summary rows, not by this guard.
    - **Migration `V4__schema_archive_and_credential_search_index.sql`** (the one additive
      migration the brief authorized, shared with Part B below): widens `credential_schema`'s
      status `CHECK` from `('DRAFT','PUBLISHED','DEPRECATED')` to add `'ARCHIVED'` — V1's baseline
      never anticipated an archive lifecycle step. The unnamed constraint's default Postgres name
      (`credential_schema_status_check`) was confirmed against a scratch container before writing
      the `DROP CONSTRAINT`/`ADD CONSTRAINT` pair, not guessed.
  - **Part B — credential search/list (KH-1.1.4 backend half)**: `GET /api/v1/credentials`,
    gated by a new `ScopeGuard.requireUserSession()` (console session only, `ACTOR_USER`, no
    specific scope, no API key of any kind — every operator role may search, but this is a console
    operator's tool, not something a `TENANT`/`CONSUMING_PARTY` integration needs). Filters — `ref`
    (exact), `pseudoRef` (exact, resolved via `holder :: api`'s new `HolderDirectory
    #findByPseudoRef`, an unknown pseudoRef short-circuits to an empty page), `schemaId` (exact),
    `revoked` (exact) — all optional, AND-combined, one paged JPQL query
    (`CredentialRepository#search`) sorted by `issuedAt` (`created_at`) DESC. `page`/`size` clamp
    to `[0, ∞)`/`[1, 100]` server-side (no 400 for an out-of-range request, just a silent clamp).
    New DTOs `CredentialSummary` (id, ref, schemaCode, schemaName, issuedAt, validTo, maxUses,
    usesRemaining, revoked — proof/status metadata only, no `sdJwt`, no claims, no pseudoRef; P1)
    and `CredentialPage` (items + page/size/totalElements/totalPages envelope — the platform's
    first paginated endpoint). Migration `V4` (above) also adds `credential_tenant_created` —
    V1 already indexed the `schemaId`/pseudoRef-resolved-`holderId` filters
    (`credential_tenant_schema`/`credential_holder`) and `ref` (globally unique), but the base
    tenant-scoped, `issuedAt`-sorted scan every call performs had no index of its own until now.
  - **Part C — idempotency race closure (KH-1.4.1/1.4.2 ruling, `docs/STATE.md`'s "Next up" #3)**:
    new `credential.domain.AtomicConsumptionRecorder` (module-private) isolates the eligibility
    decrement + `consumption_event` insert + its audit row into their own fresh transaction, on a
    real separate bean — required so `@Transactional` actually applies (a self-invoked method on
    `CredentialService` itself would bypass Spring's proxy) and so a unique-violation there rolls
    back cleanly (Postgres aborts an entire physical transaction on any statement error) before
    `CredentialService#consume` — now deliberately **not** `@Transactional` itself, the same shape
    `enforceSchemaAllowlist` already established — ever sees an aborted connection. On catch,
    `consume` confirms the winning `consumption_event` row (new `ConsumptionEventRepository
    #findByIdempotencyKey`) and answers `{consumed:true, reason:"idempotent_replay",
    usesRemaining:null}` — byte-identical in shape to the existing Redis fast-path hit, never a
    raw `KH-SYS-0500`. New test `db.ConsumeIdempotencyRaceTest`: two real threads, one shared
    `idempotencyKey`, a credential seeded with `maxUses=2` specifically (so *both* callers'
    eligibility decrement can legitimately succeed — proving this is the double-**submit** race,
    not the double-**spend** one `ConcurrentConsumeTest` already covers with `maxUses=1`), Redis
    fast-path guaranteed cold (`support.IntegrationTestSupport` wires no Redis container at all, so
    `safeRedisGet`/`safeRedisSet` silently no-op) — asserts both callers receive `consumed=true`,
    exactly one `consumption_event` row, and `uses_remaining` decremented exactly once (1, not 0).
    **Closed.**
    - **Side discovery, flagged not fixed (out of this session's narrow Part C scope):** writing
      this test surfaced a real, separate, previously-unreachable race in
      `consumer.domain.ConsumingPartyRegistryService#ensure` — its find-or-create id is
      deterministic (`UUID.nameUUIDFromBytes(tenant:code)`, by design, so the same code always
      resolves to the same row), so two callers racing to `ensure()` a **brand-new** `code`
      concurrently both see "no existing row" and both attempt an `INSERT` with the identical
      primary key, throwing `DataIntegrityViolationException` uncaught. Never manifested before
      because every prior concurrent-caller test (`ConcurrentConsumeTest`) used a distinct consumer
      code per caller specifically to avoid this; `ConsumeIdempotencyRaceTest` sidesteps it by
      calling `consumingParties.ensure(consumerCode)` once, synchronously, before spawning the
      race. Flagged in "Next up" below for whoever next touches `consumer.domain` — the fix shape
      is almost certainly the same `AtomicConsumptionRecorder`/NESTED-vs-fresh-transaction pattern
      this session just applied, or a plain `try { ensure() } catch (DataIntegrityViolationException)
      { re-read }` given `ensure()`'s id is deterministic (unlike `consumption_event`, a retry
      here can just re-SELECT by the same id and get the winner's row directly).
  - **Arabic-speaker review gate (spec FS-0.6a §4)** for the three new `schema.*` keys
    (`schema.validation-failed`, `schema.immutable-after-publish`, `schema.invalid-transition`):
    confirmed by the user (Majd) before PR #25's merge, no wording changes needed — same pattern
    as every prior session's new-key set.
  - **`docs/api/openapi.json`/`docs/error-codes.md`**: both regenerated via their own tests'
    failure-message "paste this in" content, not hand-edited — confirmed additive-only (new schema
    authoring paths/DTOs, the new `GET /api/v1/credentials` path/DTOs, three new `KH-SCH-*` rows).
  - **Tests (35 new):** `schema.domain.SchemaAuthoringServiceTest` (15 — create/update/publish/
    version/archive happy paths + audit rows, every validation-failure flavor, every invalid-
    transition flavor, `defaultValidity` ISO-8601 round-trip), `credential.domain
    .IssuanceSchemaGuardTest` (3 — issuance rejects `DRAFT`, rejects `ARCHIVED`, accepts
    `PUBLISHED`), `rbac.SchemaManagementScopeGateTest` (5 — 401/403/200 gate incl. a full HTTP
    create→update→publish→version→archive lifecycle walk + the immutable-after-publish and
    double-archive 409s), `credential.domain.CredentialSearchServiceTest` (8 — every filter
    individually, AND-combination, pagination clamp, size cap, sort order),
    `rbac.CredentialListScopeGateTest` (3 — 401/403/200, incl. a full-scope API key still 403'd),
    `db.ConsumeIdempotencyRaceTest` (1 — the race itself, described above).
- 2026-07-20: KH-1.4.3-and-schema-contract — two-part session, brief itself was the spec (no
  separate spec doc, same precedent as KH-1.6-early/KH-1.2.2): Part A schema response enrichment
  (console-blocking contract gap) + Part B KH-1.4.3 `allowed_schemas` enforcement on `/consume`.
  `mvn verify` green, 146/146 tests (4 new, up from 142). PR open against `main`, **not merged**
  (session instruction). Branch `feat/KH-1.4.3-and-schema-contract`. Confirmed `main` included PR
  #23 (KH-1.3) and KH-1.2.2 at session start via `git log` directly, per protocol.
  - **Part A — schema response enrichment**: `SchemaSummary` gains `code` (the value `POST
    /api/v1/credentials/issue`'s `schemaCode` field expects — closes the console issue screen's
    "how do I know what schemaCode values are valid" gap). `SchemaDetail` gains `code`, `sdFields`,
    `defaultMaxUses` (both already stored on `CredentialSchema`, just not surfaced), and
    `defaultValidity` — an ISO-8601 duration string (e.g. `P90D`), `null` if unset. `default_validity`
    is a Postgres `interval` column that was deliberately left unmapped on the entity since KH-0.2.1;
    rather than fighting Hibernate/JDBC `interval` type mapping, `CredentialSchemaRepository
    #findDefaultValiditySeconds` reads it as a scalar `EXTRACT(epoch FROM ...)::bigint` native query,
    and `SchemaCatalogService#toIso8601Duration` renders whole-day intervals as `PnD` (the readable,
    calendar-style form) and anything finer-grained via `Duration#toString()`'s `PT`-based form —
    both are valid ISO-8601. `IssueRequest.schemaCode`'s Javadoc gained the explicit cross-reference
    to `GET /api/v1/schemas`'s `.code` field the brief asked for. Additive only; `SchemaRef` (what
    `credential` actually depends on for issuance) is unchanged. New test:
    `SchemaReadEndpointsTest#get_withDefaultValiditySet_returnsIso8601DurationString` (direct-JDBC
    `?::interval` cast to set the column, since no service method writes it yet); existing list/detail
    tests extended with `code`/`sdFields`/`defaultMaxUses`/`defaultValidity` assertions.
  - **Part B — KH-1.4.3, `allowed_schemas` enforcement**: found the session brief's own hard
    constraint factually wrong before writing anything — there is no `allowed_schemas uuid[]` column
    anywhere in the schema; V1 always had a `consuming_party_schema` join table for exactly this
    purpose (the codebase's own `consumer/README.md`, `rbac/package-info.java`, and `CurrentActor`'s
    Javadoc already pointed at it as "KH-1.4.3 will use this"). Implemented against the join table
    instead — same deny-by-default semantics (no rows = deny all), no migration needed either way,
    so the "no migrations" hard constraint still held.
    - **B.1 (TENANT key → 403) needed no new code.** `rbac.security.SecurityConfig`'s existing
      `ScopeGuard.requireScopeAndConsumingPartyKey("consume")` rule already rejects a TENANT key
      here regardless of scope — confirmed by reading it, not assumed. This session only adds the
      explicit test (`ConsumeApiKeyGateTest#consume_withTenantKeyHavingConsumeScope_returns403`)
      the brief asked for to close the documented ambiguity.
    - **B.2 (schema scoping) is genuinely new.** `rbac.api.CurrentActor` gained `ownerId` (the
      owning `consuming_party` row's id for an `API_KEY_CONSUMING_PARTY` actor; `null` otherwise) —
      threaded through `KhatmPrincipal`/`KhatmAuthenticationToken`/`ApiKeyAuthFilter`/
      `SessionAuthenticator`/`CurrentActorResolverImpl`. `credential`'s `@ApplicationModule` gained
      `rbac :: api` as an allowed dependency (verified acyclic via `ModulithBoundariesTest` — `rbac`
      never depends on `credential`); `rbac`'s own module gained `schema :: api` (needed by
      `DemoApiKeySeeder`, below). `consumer.api.ConsumingPartyRegistry` gained
      `isSchemaAllowed`/`allowSchema`, backed by two new native queries on `ConsumingPartyRepository`
      against `consuming_party_schema` — no JPA entity for that table, the same bare-composite-key
      join-table treatment `rbac.persistence.RoleRepository` already gives `user_role`. New
      `KH-CNS-0403` (`consumer.schema-not-allowed`, both bundles) — deliberately its own code, not a
      reuse of `KH-RBC-0403` (the brief's own framing: "authenticated but this schema isn't yours" is
      support-relevant). New `AuditAction.CONSUME_SCHEMA_DENIED` (`entityRef` = credential ref,
      `detail` = `schemaId` + `party`).
    - **Snag, resolved (the one worth remembering):** the first implementation nested the
      allowlist check (audit-then-throw) inside `CredentialService#consume`'s own `@Transactional`
      boundary. Every `CONSUME_SCHEMA_DENIED` audit row was silently discarded — Spring rolls back
      the whole transaction on the unchecked `AuthorizationException`, taking the just-written audit
      insert down with it. Caught by `ConsumeApiKeyGateTest`'s own audit-row-count assertion, not by
      inspection. Fixed the same way `ClaimRedeemThrottleService#enforce` already solves the
      identical problem ahead of `ClaimRedemptionService#redeem`: the check
      (`CredentialService#enforceSchemaAllowlist`) is deliberately **not** `@Transactional`, and
      `CredentialController#consume` calls it *before* `service.consume(req)`, not from inside it —
      so the denial-path audit row commits on its own. One lean indexed read
      (`CredentialRepository#findSchemaId`, `schema_id` only) on the common/allowed path; a second
      read (full entity, for `ref`) only on the rare denial path — the hard constraint's "one indexed
      read, no N+1" satisfied for the path that actually matters.
    - **Second snag, resolved (a real regression, caught by manually re-running the demo flow, not
      by the test suite):** giving `credential.seed.DemoSeeder`/`rbac.seed.DemoApiKeySeeder` explicit
      `@Order(1)`/`@Order(2)` (so the schema exists before the party is allowlisted for it) had a
      side effect neither test suite catches — Spring sorts an *unordered* `CommandLineRunner`/
      `ApplicationRunner` as lowest precedence relative to any explicitly `@Order`ed one, not "same
      as before." `key.domain.KeyBootstrap` and `rbac.domain.AdminBootstrap` had no `@Order` (nothing
      did, before this session), so `DemoSeeder`'s new `@Order(1)` jumped it ahead of `KeyBootstrap`
      — a real `docker compose up` showed `DemoSeeder` logging "No ACTIVE issuer key for tenant" and
      skipping entirely. Fixed by giving both bootstraps an explicit `@Order(0)`. Re-verified via a
      second clean `docker compose down -v && up --build`: correct order (`KeyBootstrap` →
      `AdminBootstrap` → `DemoSeeder` → `DemoApiKeySeeder`, no warning), then a real `/consume` call
      with the demo party's logged key against the demo credential returned `{"consumed":true,...}`,
      and a second call correctly reported `already_consumed` (proving the schema-allowlist fix
      didn't disturb the existing atomic-consume domain-result shape). This is why "PR CI green" and
      "ran it locally against a live stack" both matter — neither test suite alone would have caught
      this ordering regression.
    - **Tests (4 new):** `ConsumeApiKeyGateTest` gained
      `consume_withSchemaNotInPartyAllowlist_returns403WithNewCode_andRecordsAudit`,
      `consume_withEmptyAllowlist_returns403`, `consume_withTenantKeyHavingConsumeScope_returns403`;
      the existing `consume_withValidConsumingPartyKey_works_andRecordsAuditRow` adapted (seeded
      party allowlisted via `ConsumingPartyRegistry#allowSchema`) rather than weakened.
      `ConcurrentConsumeTest` (direct `credentialService.consume()` call, no HTTP/security context)
      needed no change — `enforceSchemaAllowlist` no-ops when `CurrentActorResolver#resolve()` is
      empty, by design, since `SecurityConfig` already guarantees every real HTTP caller here has an
      actor.
  - **Arabic-speaker review gate (spec FS-0.6a §4)** for `consumer.schema-not-allowed`: confirmed
    by the user (Majd) directly before merge, no wording changes needed — same pattern as every
    prior session's new-key set.
  - **`docs/api/openapi.json`/`docs/error-codes.md`**: both regenerated via their own tests'
    failure-message "paste this in" content (`OpenApiContractTest`/`ErrorCodesDocGenerationTest`),
    not hand-edited — confirmed additive-only (new `SchemaSummary`/`SchemaDetail` fields, new
    `/consume` 403 description text, one new `KH-CNS-0403` row).
- 2026-07-20: KH-1.3 — signed status list, spec FS-1.3, D1–D7 pre-approved. `mvn verify` green,
  142/142 tests (18 new, up from 124). DONE & MERGED via PR #23 (2026-07-20, merge commit
  `9220780`); branch `feat/KH-1.3-status-list` deleted. First commit (from the prior session)
  mirrors the spec into `docs/specs/`.
  - **`status.domain.BitstringCodec`** (new, module-private): MSB-first bit-level `flipBit`/`isSet`
    over the gzip-compressed bitstring `StatusList.bitstring` already stores (KH-0.2.1) — inflate,
    touch one bit, deflate; the same compressed bytes get base64url-encoded verbatim into a
    published artifact's `bits` claim, so no double compression anywhere in the pipeline.
  - **`status.domain.StatusListRevokerService`** (new, implements the new `status.api
    .StatusListRevoker`): the D3 atomic bit-flip — `StatusListRepository#findByIdForUpdate`
    (`SELECT ... FOR UPDATE`, new) locks the row, flips the bit, bumps `version`, publishes a new
    `status.events.StatusListChanged` application event, all inside the caller's own transaction
    (`CredentialService#revoke` calls this from inside its existing revoke transaction — the
    bitstring truth and the fast-path `revoked` column now commit or roll back together, closing
    the "revoked=true but bit still 0" window the pre-KH-1.3 placeholder left open). The row lock
    is what DoD #5's two-concurrent-revokes-same-list test verifies never loses an update.
  - **`status.domain.StatusListLookupService`** (new, implements the new `status.api
    .StatusListLookup`): plain read-only `(version, uri)` resolution — no lock, safe on every
    `/verify` call per spec D6's "cheap locally" framing. Three callers pick it up (D7's promised
    placeholder-to-real swap, all additive value changes, no shape changes): `CredentialService
    #verify` gains three new additive response fields (`statusListChecked`/`statusListVersion`/
    `statusListUri`, D6 — `false`/`null`/`null` in every early-exit branch that never reached a
    credential row, populated once the row is in hand); `ClaimRedemptionService#redeem`'s
    `statusListUri` becomes the real `/sl/{tenantSlug}/{listCode}` URL, replacing the raw
    status-list UUID FS-1.2.1 §5 promised would resolve here; and — the one the "Next up" note
    from the KH-1.2.1/2 sessions specifically flagged — `CredentialService#issue` itself now bakes
    the real URL into the SD-JWT's own `status.status_list.uri` claim (spec FS-0.4 D3) at issuance
    time, not just into API responses. This last one matters most: an *offline* verifier (the
    whole point of KH-1.3, spec §1) only ever has what the token itself carries, so if this claim
    had stayed a bare UUID, offline verification would still have had nothing to resolve against.
  - **`status.domain.StatusListPublisher`** (new, public within the module — cross-sub-package
    same-module access, the `CredentialService` rationale): the one routine that signs
    (`KeySigner`, ES256, same signer/`kid` machinery SD-JWTs already use) and stores the compact
    JWS artifact (D1 payload: `list`/`ver`/`cap`/`bits`/`iat`), and the one place that decides a
    publish is actually needed — `publishIfStale` republishes only when `signedArtifact IS NULL`
    or `artifactVersion < version`, re-reading the row fresh under the same row lock every time.
    This single condition is D5's entire debounce mechanism: a storm of N rapid revokes on one
    list produces N `StatusListChanged` events, but every dispatch after whichever one first
    catches the list up to its latest version finds nothing stale left to do — DoD #4's 25-revoke
    storm test confirms exactly one publish, one audit row, and a final artifact reflecting all 25
    flipped bits.
  - **Publish has two paths to the same `publishIfStale` call, event-driven and periodic** — same
    complementary shape as `ClaimRedemptionService#redeem` (on-claim) vs. `ClaimCodeExpiryWorker`
    (periodic sweep) already established: `status.worker.StatusListChangedHandler` (new,
    implements `shared.events.StreamEventHandler`, worker-role only) consumes `StatusListChanged`
    off the **existing** `khatm.credential.events` stream — deliberately not a new stream, since
    `shared.events.RedisStreamConsumer` only polls one configured stream today and a second would
    mean a second poller/group/DLQ for no MVP benefit (documented as a deliberate call in the
    event's own Javadoc, flagged for a future session if per-stream isolation is ever actually
    needed). `status.worker.StatusListPublishSweepWorker` (new, worker-role only, mirrors
    `ClaimCodeExpiryWorker`'s exact `@Scheduled` shape) is the safety net —
    `StatusListRepository#findStaleIds` (new query) finds every list with `signedArtifact IS NULL
    OR artifactVersion < version` and republishes each; interval `khatm.status.publish.debounce`
    (default 2000ms, comfortably inside NFR-06's ≤60s budget).
  - **`status.web.StatusListController`** (new): `GET /sl/{tenantSlug}/{listCode}` — public,
    `application/jose`, `ETag` = quoted version, `Cache-Control: max-age=60` (D2). **Lazy-publish
    fallback**: if a list has never been published (fresh allocation, sweep hasn't reached it yet),
    the request thread calls the same `publishIfStale` inline before serving, so the endpoint is
    never a 404 waiting on a scheduler tick — reuses the publisher verbatim, no JWS-construction
    duplication. `tenantSlug` is checked against `TenantContext.currentSlug()` and 404s on
    mismatch (single-tenant MVP; real per-request tenant resolution is KH-2.1). New
    `KH-STS-0404`/`status.not-found` (the first `status` lookup exposed over HTTP that can
    actually fail) in both message bundles.
  - **`rbac.security.SecurityConfig`**: `/sl/**` added to the public list — **now four** entries
    (`/verify`, JWKS, `/claims/redeem`, `/sl/**`), enforced identically on both filter chains via
    the existing `configureAuthorization`; `PublicEndpointsNoCredentialsTest` extended to match.
    `WorkerProfileSecurityBootTest`'s absence-list gained `statusListController` (worker role must
    never serve business REST, ADR-09 — same pattern every prior controller addition follows).
  - **New `AuditAction.STATUS_LIST_PUBLISHED`** (actor always SYSTEM — published from a worker
    consumer or sweep tick, never a request thread; `entityRef` = `list_code`, `detail.version` —
    never the bitstring or the artifact itself, SEC §9) recorded once per actual publish, not once
    per `StatusListChanged` event, which is exactly what the DoD #4 storm test's single-audit-row
    assertion pins.
  - **`V3__status_list_artifact.sql`** — the second post-baseline migration (append-only, V1/V2
    untouched): `status_list` gains `signed_artifact text` (the JWS itself) and `artifact_version
    bigint NOT NULL DEFAULT 0`. The pre-existing `signed_artifact_ref` column (V1, Phase-2 external
    storage pointer) is untouched and still unused. `MigrationImmutabilityTest`/clean-boot green;
    checksum appended to `db/migration-checksums.lock`.
  - **New `khatm.platform.base-url` config** (`status.domain.StatusListUriBuilder`, new) — not a
    secret, always has a default (`http://localhost:8080`, env `KHATM_PLATFORM_BASE_URL`), same
    treatment as `khatm.issuer-did`; builds the fully-qualified public `/sl/{tenantSlug}/{listCode}`
    URL every `StatusListRef` carries.
  - **Tests (18 new)**: `status.domain.StatusListDomainTest` (3 — BitstringCodec round-trip,
    revoke flips bit + bumps version + resolves via lookup, DoD #5's two-concurrent-revokes-
    same-list real-thread race), `status.domain.StatusListPublishTest` (3 — DoD #2
    sign/store/audit + idempotency, DoD #4's 25-revoke storm → one publish with every bit
    reflected, sweep catch-up), `status.domain.NoBitstringContentInLogsTest` (1 — DoD #9, the
    published artifact string never appears in a log line across an allocate→revoke→publish
    cycle), `status.web.StatusListControllerHttpTest` (4 — DoD #3: 200 with zero credentials +
    valid JWS claims, matching `If-None-Match` → 304 no body, unknown list → 404, wrong tenant
    slug → 404), `status.worker.StatusListChangedWorkerTest` (1 — DoD #3's event-driven half
    specifically: real outbox→stream→consumer-group round trip with the periodic sweep interval
    set to an hour, so only the event path could have produced the publish asserted on),
    `credential.domain.StatusListVerifyAndRedeemIntegrationTest` (5 — DoD #6: `/verify`'s three
    additive fields on a valid credential, on a revoked one with version strictly advanced, and
    absent on a malformed presentation; redeem's `statusListUri` is a real URL, not the old
    bare-UUID placeholder; the SD-JWT's own embedded `status.status_list.uri` claim at issuance is
    real too — the offline-verification case D7 actually exists for). `rbac
    .PublicEndpointsNoCredentialsTest` extended to four endpoints (+1);
    `shared.events.WorkerProfileSecurityBootTest`'s existing test extended with the
    `statusListController` absence check (no new test method, so no count change there).
    `OpenApiContractTest` regenerated the published contract additively (the new
    `/sl/{tenantSlug}/{listCode}` path) — confirmed via the test's own freshness gate.
  - **Side note (test-writing snag, resolved)**: `StatusListAllocatorService#allocate` bumps
    `status_list.version` on every allocation too (pre-existing KH-0.2.1 behavior, not something
    this session changed) — several new tests initially hardcoded an absolute post-revoke version
    and failed until rewritten to assert the version *delta* the revoke itself produced, the same
    lesson `ClaimCodeMintServiceTest`'s `valid_to > valid_from` CHECK-constraint snag taught last
    session in a different shape: don't assume a fixture's starting state, read it.
  - **Arabic-speaker review gate (spec FS-0.6a §4)** for `status.not-found`: **pending** — flag in
    the PR body before merge, same as every other session's new-key set.
- 2026-07-19: KH-1.2.2 — expose claim-code minting, `POST /api/v1/credentials/{id}/claim-code`
  (spec FS-1.2.1 D2's re-issue recovery path exposed over HTTP; no separate spec doc — the session
  brief itself was the spec, same precedent as KH-1.6-early). `mvn verify` green, 124/124 tests (12
  new, up from 112 — 6 service-level `ClaimCodeMintServiceTest`, 5 HTTP scope-gate
  `ClaimCodeMintScopeGateTest`, 1 `NoDisclosureContentInLogsTest` extension). DONE & MERGED via PR
  #22 (2026-07-19, merge commit `871c51b`); branch `feat/KH-1.2.2-claim-code-endpoint` deleted.
  - **`CredentialService#mintClaimCode`** (new, public — same module-privacy rationale as every
    other `CredentialService` method): finds the credential (else `NotFoundException`
    `KH-CRD-0404`, same code/message `GET`/`revoke` already use — no new "foreign-tenant" handling
    needed, since the platform is still single-tenant and every existing lookup in this module
    already works this way), rejects a revoked-or-expired one (new `ConflictException`
    `KH-CRD-0409`, one message for both flavors — the caller here is always an authenticated
    issuer, not an external prober, so there was no D5-style anti-probing reason to split them),
    voids any prior still-live claim code for the credential (new `ClaimCodeRepository
    #zeroPendingForCredential`, the exact same `disclosures_enc`-zeroing shape
    `#zeroExpiredUnclaimed` already uses, just scoped by `credential_id` instead of by expiry), then
    delegates to the existing `#issueClaimCode` unchanged — genuinely "no new domain mechanics,"
    per the session brief. Requires the caller to supply the original `sdJwt` presentation string
    in the request body: the platform never persists a presentation's disclosures outside a
    `claim_code` row (P1), so minting a code for an already-issued credential is only possible if
    the issuer retained that one-time delivery — exactly the recovery case FS-1.2.1 D2 describes
    ("the issuer re-issues a claim code"). `ttlMinutes` optional, defaults to 15.
  - **`credential.web.CredentialController#mintClaimCode`** (new): `POST
    /api/v1/credentials/{id}/claim-code`, thin (validate → call service → map). Full OpenAPI
    annotations, including the required cross-reference to `/api/v1/claims/redeem`'s QR v1 payload
    description (the minted `code` is what a console issue screen would encode into that JSON).
  - **`rbac.security.SecurityConfig`** gained one new path rule: `POST
    /api/v1/credentials/*/claim-code` reuses `/issue`'s exact
    `ScopeGuard.requireScopeNotConsumingPartyKey("issue")` rule verbatim (session brief: "session
    or TENANT API key with scope issue") — documented as a deliberate, explicit per-endpoint
    decision in the class Javadoc (CONVENTIONS §7.2), not the silent authenticated-any-scope
    default.
  - **New `KH-CRD-0409`** (`ErrorCode`/`credential.not-claimable`) — the first `CRD` code with a
    status other than 404; both message bundles updated in the same commit (Arabic review gate
    pending — flagged below, same as every prior session's new-key set). **New
    `AuditAction.CLAIM_CODE_ISSUED`** — `entityRef` is the credential's ref, never the code (actor
    attribution is automatic via `AuditService` reading `SecurityContextHolder`, same as every
    other audited action; no explicit actor-passing needed in the new code).
  - **Tests (12 new)**: `ClaimCodeMintServiceTest` (happy path + audit row, custom `ttlMinutes`
    honored, **second mint voids the first still-live code** — asserted both by code identity and
    by a DB count of rows with `disclosures_enc IS NOT NULL AND claimed_at IS NULL` staying at
    exactly 1 while both rows still exist, unknown-credential 404, revoked-credential 409,
    expired-credential 409), `ClaimCodeMintScopeGateTest` (rbac package, mirrors
    `ScopeGateTest`/`ConsumeApiKeyGateTest`'s established pattern: no-credentials 401,
    `CONSUMING_PARTY` key 403, `TENANT` key missing `issue` scope 403, `TENANT` key with `issue`
    scope 200, `ISSUER_OPERATOR` session 200), `NoDisclosureContentInLogsTest` extended with a
    mint-then-mint-again cycle (neither raw code, neither salt, nor the plaintext claim value ever
    appears in a log line). `OpenApiContractTest` regenerated the published contract additively —
    confirmed via the test's own freshness gate, not just assumed.
  - **Side note (test-writing snag, resolved)**: the DB's `CHECK (valid_to > valid_from)` on
    `credential` means the expired-credential test can't push only `valid_to` into the past — it
    must move `valid_from` back too (both set to before "now," `valid_to` still after
    `valid_from`), otherwise the raw JDBC `UPDATE` itself is rejected by the constraint before the
    service method under test ever runs.
  - **Arabic-speaker review gate (spec FS-0.6a §4)** for `credential.not-claimable`: **pending** —
    flag in the PR body before merge, same as every other session's new-key set.
- 2026-07-18: KH-1.2.1 — claim delivery, spec FS-1.2.1, D1–D8 pre-approved. `mvn verify` green,
  112/112 tests (14 new, up from 98). DONE & MERGED via PR #21 (2026-07-19, merge commit `165b022`);
  branch `feat/KH-1.2.1-claim-delivery` deleted. First commit mirrors the spec into `docs/specs/`.
  - **Part 0 (convention promotion, first)**: `docs/CONVENTIONS.md §9` gained a paragraph on the
    static-initializer singleton-container test-support pattern — bitten twice already
    (`RbacHttpTestSupport` at KH-0.6b, `ErrorEnvelopeTestSupport` at KH-1.6-early), both cited by
    session name so a third bite can't happen unnoticed.
  - **`credential.domain.ClaimRedemptionService#redeem`** (new, public — module-private via
    Modulith, `credential.web.ClaimController` in a different sub-package needs it): the D2
    single-transaction flow — `ClaimCodeRepository#findByCodeHashForUpdate` (`SELECT ... FOR
    UPDATE`, new) locks the row, validates (not claimed, not expired, `disclosures_enc` still
    populated), decrypts via the existing `ClaimsEncryptionService` (KH-0.4, its `decrypt()` had
    no production caller until now), sets `claimed_at`, zeroes `disclosures_enc`, records
    `CLAIM_CODE_REDEEMED`, and returns a `ClaimRedeemResult` built entirely from the in-memory
    decrypted material — `ClaimController` only assembles the HTTP response from that returned
    value, which happens after Spring's transactional advice has already committed. Every failure
    flavor (unknown/malformed/expired/already-claimed/expiry-zeroed) collapses to the identical
    `NotFoundException(KH-CLM-0404)` (D5) — none of them audited individually (D7). The `FOR
    UPDATE` lock is what makes a redeem race-safe against `ClaimCodeExpiryWorker`'s concurrent
    sweep touching the same row.
  - **`credential.domain.ClaimRedeemThrottleService`** (new, public, same module-privacy
    rationale): per-IP Redis fixed-window counter (`khatm:claims:redeem:throttle:{ip}`, same
    increment-then-set-TTL-once shape as `rbac.domain.AuthService`'s login lockout), config
    `khatm.claims.redeem.throttle.max-attempts`/`.window` (defaults 10/1m). Trips → `429
    KH-CLM-0429` — **rides on `ValidationException`**, a deliberate judgment call flagged in the
    class Javadoc for PR review: none of CLAUDE.md's six `KhatmException` subtypes was written
    with HTTP 429 in mind (the `rbac` module's own analogous lockout counter reuses
    `AuthenticationException`/401 instead), and adding a seventh subtype would silently
    invalidate CLAUDE.md's documented "six subtypes" list without an explicit approved
    instruction to do so. Every attempt counts toward the window, successful or not; `X-Forwarded-
    For` deliberately not read (no reverse proxy locally yet — spec D6). The one failure flavor of
    this endpoint that IS audited individually: `CLAIM_REDEEM_THROTTLED` (IP + count).
  - **New `KH-CLM` error-code tag** (`ErrorCode`/`CONVENTIONS.md §2`): deliberately the one tag
    that names a bounded concern rather than its owning Java module 1:1 — claim-delivery lives
    inside `credential` (no new Modulith module, per the task's hard constraint) but is a
    conceptually separate, wallet-facing failure vocabulary from `CRD`'s. `KH-CLM-0404` (404,
    `error.clm.invalid_or_expired`) and `KH-CLM-0429` (429, `error.clm.throttled`) — both bundle
    keys added in both languages same commit (Arabic review gate still pending — see below).
  - **`credential.web.ClaimController`** (new): `POST /api/v1/claims/redeem`, thin (throttle →
    validate → call service → map). `rbac.security.SecurityConfig` gained its third public entry
    (`CLAIMS_REDEEM_PATH`, alongside `/verify` and JWKS) — no explicit CSRF-ignore entry needed,
    the existing no-session-cookie exemption already covers it, the same as `/consume`. Full
    OpenAPI annotations including **QR contract v1 (D8) embedded verbatim** in the endpoint's
    `@Operation` description: `{"v":1,"api":"<platform base URL>","code":"<claim code>"}` —
    confirmed present in the published `docs/api/openapi.json` byte-for-byte.
  - **`disclosures_enc` blocker: CLOSED FOR GOOD.** All three thirds: encryption (KH-0.4,
    `CredentialService#issueClaimCode`), expiry-zeroing (ADR-09-worker,
    `ClaimCodeExpiryWorker#sweep`), on-claim zeroing (this session,
    `ClaimRedemptionService#redeem`). `ClaimCode`'s own Javadoc rewritten to describe the real,
    now-complete picture instead of the old "KH-1.2.1 not done yet" placeholder note.
  - **Tests (14 new)**: `ClaimRedemptionServiceTest` (happy path incl. zero-claims-empty-
    disclosures-list edge case, double-redeem 404, expired 404, expiry-sweep-zeroed 404, unknown
    404), `ClaimRedemptionConcurrencyTest` (20 concurrent redeemers of the same code → exactly 1
    success, real separate threads/transactions, not sequential calls; a genuine redeem-vs-sweep
    race on an already-expired row, latch-synchronized, asserting neither double delivery nor
    delivery of zeroed material), `ClaimControllerHttpTest` (D4 response shape over real HTTP,
    zero-credentials happy path, double-redeem 404), `ClaimRedeemThrottleHttpTest` (Nth attempt
    trips 429 + one audit row, recovers after the window — test-scoped 5/2s throttle config now
    lives in `RbacHttpTestSupport` itself, same place the analogous lockout override already was),
    `PublicEndpointsNoCredentialsTest` extended to exactly three (D9→D7 extension), `NoDisclosure-
    ContentInLogsTest` extended with a redeem-cycle method (no claim value, salt, or the raw code
    itself ever in a log line).
  - **Side fixes forced by the new concurrency test, in the same PR**: (1) `RbacHttpTestSupport`
    gained a `@BeforeEach` that clears the claim-redeem throttle's Redis keys — unlike the
    lockout counter (scoped per random test username), the throttle counter is scoped per source
    IP, and every subclass's `TestRestTemplate` shares one loopback address, so without this reset
    one class's incidental `/redeem` calls could silently eat into another's throttle budget
    depending on JUnit's test-class execution order. (2) The redeem-vs-sweep concurrency test
    genuinely commits real data (it must, for cross-connection visibility) rather than relying on
    `@Transactional` rollback like `ClaimCodeExpirySweepTest`'s tests do — but `audit_log` is
    append-only (trigger-enforced, CLAUDE.md), so its `CLAIM_CODES_EXPIRED` audit row from
    `ClaimCodeExpiryWorker#sweep` can never be cleaned up afterward. `ClaimCodeExpirySweepTest`'s
    two audit-row-count assertions were absolute (`COUNT(*) = N`), which only ever worked because
    every *prior* writer of that action was itself `@Transactional`-rolled-back; converted both to
    before/after deltas, which is what they should have asserted all along in a shared-context
    suite. (3) Two Hibernate first-level-cache staleness bugs surfaced by wrapping two service-
    level tests in `@Transactional` for their own cleanup (needed because *they* deliberately leave
    an expired-but-populated row on the rejection path, which the append-only-log problem above
    doesn't apply to since claim_code isn't append-only): a raw `jdbc.update()` setting
    `expires_at` into the past needs an `entityManager.flush()` immediately before it (the
    just-`save()`d entity hasn't hit the DB yet within one shared test transaction — unlike the
    non-transactional happy-path tests, where each service call auto-commits) and an
    `entityManager.clear()` immediately after (so a subsequent JPQL identity-SELECT inside
    `redeem()` doesn't hand back the stale, pre-update cached instance instead of re-reading the
    row) — the exact `NoDisclosureContentInLogsTest`/`ClaimCodeExpirySweepTest` idiom, just not
    obviously required until a *third* consumer of the pattern (this session's tests) actually hit
    both failure modes in sequence.
  - **Arabic-speaker review gate (spec FS-0.6a §4)** for `error.clm.invalid_or_expired`/
    `error.clm.throttled`: confirmed by the user (Majd) directly before merge, no wording changes
    needed — same pattern as every prior session's new-key set.
- 2026-07-18: chore/state-update-post-pr19 — planned as a pure STATE.md merge record for PR #19,
  but its own CI run (`compose-smoke`) failed on a docs-only diff, which CONVENTIONS §11 treats as
  a hard merge blocker ("no exceptions") — investigated rather than bypassed, per CLAUDE.md's
  root-cause-first stance, and turned up two real, previously-undiscovered bugs:
  - **`rbac.domain.AdminBootstrap`'s `api`/`worker` concurrent-boot race**: identical shape to the
    `key.domain.KeyBootstrap` race fixed at KH-0.3-closure (both roles running an idempotent
    `ApplicationRunner` unconditionally against one shared, freshly-created database), just never
    caught for this class at the same time. Confirmed via the actual CI log: `khatm-api` crashed
    at startup with `PSQLException: duplicate key value violates unique constraint
    "app_user_tenant_id_username_key"` — both containers' `bootstrapIfNeeded()` saw
    `users.existsByTenantId(tenantId)` as `false` simultaneously and both attempted the insert.
    Fixed with the exact same `@ConditionalOnProperty(khatm.web.enabled, matchIfMissing=true)` gate
    `KeyBootstrap` already carries, so only the `api` role ever runs it. New
    `rbac.domain.AdminBootstrapRoleGuardTest` (mirrors `key.domain.KeyBootstrapRoleGuardTest`
    exactly); `shared.events.WorkerProfileSecurityBootTest`'s worker-role absence-check list
    gained `adminBootstrap` alongside the existing `keyBootstrap`/controller entries. **Verified
    for real, not just via CI**: `scripts/smoke.sh` re-run locally end-to-end from a clean `docker
    compose down -v` — both boot phases passed.
  - **`OpenApiContractTest`'s freshness-gate comparison was platform-dependent**: Jackson's default
    pretty printer (`writerWithDefaultPrettyPrinter()`) inserts `System.lineSeparator()` between
    object fields — `\r\n` on Windows, `\n` on the Linux CI runners. A contract regenerated on a
    Windows dev machine could therefore never byte-match the git-normalized (`.gitattributes`
    `eol=lf`) committed file, regardless of whether the actual OpenAPI content had drifted at all;
    this had been silently masked until now because every prior local generation/comparison on
    this branch happened to compare two CRLF copies against each other (before either side had
    been through a real git commit-then-checkout cycle). Fixed by constructing an explicit
    `DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"))` — deliberately
    **not** overriding the array indenter too, since Jackson's own default there
    (`FixedSpaceIndenter`, keeps short arrays inline like `["TENANT", "CONSUMING_PARTY"]`) is what
    the already-committed `docs/api/openapi.json` uses; overriding both would have reformatted
    every array as an unrelated cosmetic change. Re-verified deterministic across three consecutive
    local runs after the fix.
  - Both fixes bundled into this same PR (not a separate branch) since they were direct blockers
    discovered while trying to get *this* PR's CI green, in the spirit of the swagger session's
    "flagged and fixed in the same PR" precedent for the analogous `KeyBootstrap` race.
  - `mvn verify` green throughout (98/98 tests after both fixes, re-run from clean each time, not
    assumed).
- 2026-07-18: KH-1.6-early — `/api/v1` path migration + full OpenAPI coverage + published contract
  + read-only schema endpoints. No spec doc; the session brief itself was the spec, four parts.
  `mvn verify` green, 95/95 tests. DONE & MERGED via PR #19 (merge commit `652aa73`, fast-forward);
  branch deleted. The Arabic-speaker review gate for the new `schema.not-found` key ran in a
  follow-up exchange (user confirmed directly, no wording changes) before merge. Confirmed `main`
  included PR #18 at session start (checked `git log`/`gh pr view` directly rather than trusting
  this file, which — see the PR-list correction above — had gone stale on that exact point).
  - **Part 1 — `/api/v1` migration (the breaking change)**: `credential` endpoints were already
    under `/api/v1/credentials/**` (since KH-0.4), so this session's actual scope was narrower
    than the brief's worst case — only `rbac.web.AuthController`'s five endpoints moved:
    `/api/auth/{login,logout,me}` → `/api/v1/auth/{login,logout,me}`, `/api/admin/api-keys` (+
    `/{id}/revoke`) → `/api/v1/admin/api-keys` (+ `/{id}/revoke`). No aliases, no redirects — the
    old paths simply don't exist on this branch. `SecurityConfig`'s `LOGIN_PATH`/`ADMIN_PATH`
    constants, `ScopeGuard`'s Javadoc, `scripts/smoke.sh`, the `rbac` README, and every rbac HTTP
    test's path literals moved together in the same commit; re-ran the full rbac HTTP suite
    (login/lockout/logout/me, admin api-key create/revoke, scope/consume/public-endpoint gates)
    to confirm nothing broke silently.
  - **Part 2 — full OpenAPI coverage**: `CredentialController`'s `consume`/`revoke`/`get` gained
    the same `@Operation`/`@ApiResponse` annotation quality `/issue`/`/verify` already had (KH-0.4).
    New `OpenApiContractTest#everyRestControllerMapping_hasAMatchingOperationInApiDocs` enforces
    it going forward — **implemented as a source-text scan over `src/main/java` (same technique
    `NoDirectAuditLogInsertTest` uses), not live reflection over `RequestMappingHandlerMapping`**:
    reflection would also pick up framework-internal controllers (Boot's `BasicErrorController`,
    springdoc's own `/v3/api-docs` handler) that were never annotated and never should be, making
    "how many operations should exist" ambiguous. A source scan restricted to `src/main/java`
    counts exactly this platform's own endpoints and naturally excludes `TestBoomController`
    (lives under `src/test/java`).
  - **Part 3 — published contract**: `docs/api/openapi.json` generated by a context-booting test
    (`OpenApiContractTest`, extends `shared.web.ErrorEnvelopeTestSupport`), **not** the
    springdoc-openapi-maven-plugin — recorded per the brief's "record which was used": that plugin
    needs a real listening instance of the app during the `mvn` build itself, which in this
    codebase means standing up Testcontainers-backed Postgres/Redis/keystore wiring outside the
    test lifecycle that already builds it, for no benefit over reusing the test context. The same
    test class is the freshness gate (second `@Test` method) — fetches `/v3/api-docs` over a real
    HTTP call (an API key with an unrelated scope, proving `/v3/api-docs` itself needs no specific
    scope), canonicalizes it (recursive alphabetical key sort — springdoc's internal map ordering
    isn't a documented guarantee, so this test cares about content drift, not incidental
    reordering), strips `/api/v1/_test/**` paths (the `TestBoomController` fixture — real in the
    booted test context, never shipped) and the `servers[0].url` field (springdoc fills this from
    the test's own ephemeral random port; comparing it would fail on every single run for a reason
    with nothing to do with an actual contract change), and byte-compares against the committed
    file — same self-serve philosophy as `ErrorCodesDocGenerationTest`, fails with the exact
    content to paste in in the assertion message. **Generation snag, resolved**: initially tried
    extracting that "paste this in" content straight from a redirected `mvn` console log; on
    Windows Git-Bash this silently corrupted multi-byte UTF-8 (em dashes) into malformed bytes
    that then failed `Files.readString` on the *next* run trying to read the corrupted committed
    file back — switched the failure path to write the generated content straight to a
    `target/openapi-generated.json` file via `Files.writeString` instead of relying on console
    capture, sidestepping the console codepage entirely. README gained an "API contract" section.
  - **Part 4 — read-only schema endpoints**: `schema :: api` gained `SchemaSummary`/`SchemaDetail`
    DTOs and `SchemaCatalog#listAll`/`#findDetailById` (the existing `SchemaRef`/`ensurePublished`/
    `findById` surface `credential` depends on is untouched). New `schema.web.SchemaController`:
    `GET /api/v1/schemas` (id, name_i18n, version, status) and `GET /api/v1/schemas/{id}` (adds
    claims_def). `SecurityConfig` gained an explicit `SCHEMAS_PATH` matcher —
    `.authenticated()`, no `ScopeGuard` rule — documented in both the class Javadoc and the
    endpoints' own `@Operation` descriptions as a deliberate choice (read-only tenant metadata
    every actor kind may see), not the silent authenticated-any-scope default CONVENTIONS §7.2
    warns against relying on implicitly. New `KH-SCH-0404` — the first schema lookup that can
    actually fail (every prior `SchemaCatalog` caller finds-or-creates or degrades gracefully);
    `schema.not-found` added to both message bundles + `docs/error-codes.md`. The Arabic-speaker
    review gate (spec FS-0.6a §4) for the new `schema.not-found` string ran before merge (user
    confirmed directly, no wording changes needed).
  - **Side fix (pre-existing bug, found by adding a second subclass)**:
    `shared.web.ErrorEnvelopeTestSupport` used `@Testcontainers`/`@Container` — the exact pattern
    `rbac.RbacHttpTestSupport`'s own Javadoc already documents as broken for a base class with
    more than one subclass sharing a cached Spring context (the first subclass's `afterAll` stops
    the container out from under every sibling). It only had one subclass
    (`ErrorEnvelopeAndI18nTest`) before this session; `OpenApiContractTest` becoming the second
    reproduced the bug for real — `mvn verify` failed with `CannotCreateTransactionException`
    against a dead connection pool. Converted to the same manual static-initializer pattern
    `RbacHttpTestSupport`/`IntegrationTestSupport` already use (start once, never stop; Ryuk reaps
    at JVM exit). Confirmed fixed by re-running both subclasses together, and again inside the
    full `mvn verify`.
  - **Tests**: `SchemaReadEndpointsTest` (list/detail/404-with-KH-SCH-0404/API-key-with-unrelated-
    scope-still-works/no-credential-401), `OpenApiContractTest` (coverage + freshness, both
    described above). Every existing rbac HTTP test's path literals updated; all re-ran green.
- 2026-07-18: chore/swagger-and-flagged-fixes — local Swagger UI + the two KH-0.3-closure follow-up
  flags (CVE-2026-22732, `KeyBootstrap` race) + STATE hygiene. No spec; the session brief itself
  was the spec, four parts. `mvn verify` green throughout (re-run after each part, not only at the
  end, per the brief). PR #18 open against `main`, **not merged** (brief said not to).
  - **Part 1 — Swagger UI, local/dev only**: swapped `springdoc-openapi-starter-webmvc-api` for
    `-webmvc-ui` (pulls the `-api` artifact in transitively). **Version pinned to 2.6.0, not the
    2.8.x line the old `-api` artifact used** — discovered empirically, not from a compatibility
    table alone: 2.7.0+ references Spring Framework 6.2's
    `org.springframework.web.servlet.resource.LiteWebJarsResourceResolver` from its UI
    auto-configuration, unconditionally enough to throw `NoClassDefFoundError` at context startup
    on Spring Boot 3.3.13's Framework 6.1 line (this pom's actual, CLAUDE.md-frozen pin). The old
    `-api`-only artifact never exercised that code path (no UI static resources), so the same
    2.8.x/Boot-3.3.x mismatch was latent and harmless until this session's UI swap actually hit
    it. 2.6.0 is the last springdoc-openapi release still targeting Boot 3.0.x–3.3.x. `rbac.security
    .SecurityConfig`: both `SecurityFilterChain` beans now take an `Environment` parameter and
    permit `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` only when
    `environment.matchesProfiles("local", "dev")` is true — computed per chain build, not a third
    permanent entry alongside D9's public two; outside those profiles the paths fall through to
    `anyRequest().authenticated()` and 401 like anything else. `shared.config.OpenApiConfig` (new)
    supplies the `OpenAPI` bean (title "Khatm Platform API", version from `@project.version@` —
    Maven resource filtering, already enabled for `application.yml` by the inherited
    `spring-boot-starter-parent` POM, so **no build-plugin change was needed** to get a real
    version string). `springdoc.swagger-ui.csrf.enabled=true` (application.yml) makes Swagger UI's
    own "Try it out" read the `XSRF-TOKEN` cookie and echo `X-XSRF-TOKEN` automatically. `@Tag`
    added to `AuthController`/`CredentialController`/`JwksController` (auth/credential/jwks
    groups). Two new tests (`rbac.SwaggerUiAccessTest`/`SwaggerUiLocalEnabledTest`, the latter
    merging `local` onto `RbacHttpTestSupport`'s `test` profile via `@ActiveProfiles`'s default
    `inheritProfiles=true`) prove 401 outside local/dev and 200 (JSON parses, UI page renders)
    inside it. `docs/deploy-staging.md` gained a one-line note that Swagger stays off on staging
    until KH-1.6. Manually verified against a live `docker compose` stack (see Part 3): `/v3/api-
    docs` and `/swagger-ui/index.html` return 200 unauthenticated under `local,api`; a full
    login → `/api/auth/me` (forces the XSRF-TOKEN cookie) → `X-XSRF-TOKEN`-bearing `/issue` call
    — the exact mechanics Swagger UI's own CSRF handling uses — succeeded end-to-end via curl.
  - **Part 2 — CVE-2026-22732**: tried the brief's preferred path first — a `dependencyManagement`
    override of `spring-security-web` alone to 6.5.9, everything else left at the Boot BOM's
    6.3.10 — and it resolved fine but **broke at runtime**, confirmed by actually running the
    suite, not assumed: every `@SpringBootTest` hitting `requestMappingHandlerAdapter` failed with
    `NoClassDefFoundError: org/springframework/security/core/annotation/SecurityAnnotationScanners`,
    a class `spring-security-web` 6.5.9 references that only exists in `spring-security-core`
    6.4+. Fell back to the brief's documented contingency: override the whole
    `spring-security.version` property to `6.5.9` instead, moving config/core/crypto/test/web
    together. `mvn verify` green after (81+ tests, zero failures across all surefire reports).
    `.trivyignore`'s `CVE-2026-22732` entry deleted; `trivy fs` (run locally via the `aquasec/
    trivy:0.72.0` Docker image, matching CI's pinned binary version) reports 0 CRITICAL/HIGH on
    `pom.xml` without it — the CVE is genuinely closed, not just allowlisted differently.
  - **Part 3 — `KeyBootstrap` race, fixed**: added
    `@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing =
    true)` to `key.domain.KeyBootstrap` — the exact ADR-09 shape `CredentialController`/
    `JwksController` already use — so only the `api` role ever bootstraps the shared PKCS#12
    keystore. New `key.domain.KeyBootstrapRoleGuardTest` (lightweight `ApplicationContextRunner`,
    mirrors `shared.events.WorkerRoleGuardTest`'s pattern, mocked `KeyLifecycleService`): bean
    present with `khatm.web.enabled=true` or unset (api/local/default's `matchIfMissing` shape),
    absent with `khatm.web.enabled=false`. `shared.events.WorkerProfileSecurityBootTest` (the
    existing full-context DoD #9 worker-boot test) extended with a `context.getBean("keyBootstrap")`
    absence assertion alongside its existing controller-absence checks. `scripts/smoke.sh`'s
    `boot_stack` reverted to one plain `docker compose up -d --build` (api+worker concurrently,
    unsequenced) — the sequenced-boot workaround deleted along with the bug it patched (fixing the
    revert also required re-adding a `wait_for_api` call after `boot_stack` that the simplification
    had accidentally dropped, caught by the first local smoke run failing with "Empty reply from
    server" before Tomcat had even started listening). **Ran the unsequenced smoke script 3 times
    from a clean `docker compose down -v`** (6 full boot cycles counting each run's own
    down-v/reboot restore-from-zero phase) — all 6 passed; `khatm-worker`'s logs never show a
    `KeyBootstrap` line at all across any run (confirmed via `docker compose logs khatm-worker |
    grep -i keybootstrap` — no output), while `khatm-api`'s always shows exactly one
    `Bootstrapped issuer key kid=...` line. The race is closed, not just less likely.
  - **Part 4 — STATE hygiene**: deleted the stale "KH-0.6b `messages_ar.properties` … not yet had
    the native-speaker review gate" blocker — contradicted by this file's own top section (the
    gate ran in the PR #14 merge session, reviewed by Majd, no changes needed). Moved the
    CVE-2026-22732 and `KeyBootstrap` entries out of "Open decisions / blockers" (both CLOSED by
    this session, per the two paragraphs above) — the CVE's original KH-0.3-closure write-up
    gained a forward-pointer to this entry instead of being deleted outright, and the KH-0.5.1
    decision explaining why `KeyBootstrap` ran in every profile gained a one-line "superseded"
    note (it now runs in every *profile*, but only the `api` *role*).
  - See "Decisions made" → Session chore/swagger-and-flagged-fixes.
- 2026-07-18: KH-0.3 Phase-0 closure — DevOps gates + one docs promotion (**no application code**;
  the only `pom.xml` edits are the Trivy-driven patch-level dependency bumps, Part 1). DONE &
  MERGED via PR #16 (2026-07-18, merge commit `b7b5342`, fast-forward — `main` had not diverged
  since the branch was cut); branch `chore/KH-0.3-phase0-closure` deleted. `mvn verify` green,
  81/81 tests. **PR #16's CI was fully green before merge**: all four jobs passed (`verify`,
  `trivy`, `gitleaks`, `compose-smoke`) — three real CI-config bugs were found and fixed via the
  PR's own CI runs, not assumed correct: `gitleaks` needed `pull-requests: read` to list PR commits
  (403 without it); `trivy-action`'s `version:` input needs the git-tag `v` prefix (`v0.72.0`, not
  `0.72.0`); `trivy fs`'s independent pom.xml resolver needed a pre-warmed `~/.m2` (via
  `dependency:go-offline` before the scan) to avoid tripping Maven Central's rate limit on a cold
  runner. **Closes every Phase-0 exit criterion** except KH-0.3.3 deploy activation, which is
  explicitly a config task.
  Five parts + one forced build-infra fix:
  - **Build-infra fix (forced by Part 1/3/4, not part of the brief's code scope)**: the `Dockerfile`
    built on Temurin 17 while `pom.xml` is `--release 21`, so `docker build` failed at `mvn package`
    — making every image-dependent job impossible. Bumped both stages to `eclipse-temurin:21`. No
    app / migration / pom change.
  - **Part 0 (docs promotion)**: the two "Immediate note for future sessions" blocks (work-rules 2&3
    same-commit discipline; KH-0.6b Spring Security per-endpoint discipline) promoted into a new
    `docs/CONVENTIONS.md §7` (Security & error-handling conventions); old §7–10 renumbered §8–11
    (one internal §8→§9 cross-ref fixed). STATE.md holds one-line pointers; CLAUDE.md gained a §7
    pointer line and a `docs/specs/` read-only-mirror rule. No fifth work rule added.
  - **Part 1 — KH-0.3.2 (Trivy gate)**: new `trivy` CI job — `trivy fs` (deps) + `trivy image`
    (runtime image), fail on CRITICAL/HIGH, `--ignore-unfixed`, DB cached daily. `aquasecurity/
    trivy-action` is **SHA-pinned to v0.36.0** — aquasecurity/trivy-action was the target of a
    2026-03-19 supply-chain attack (force-pushed tags + malicious v0.69.4) and has a command-injection
    CVE in ≤0.33.1; the Trivy binary is also pinned to `0.72.0`. Other third-party actions stay
    tag-pinned (this repo's style); trivy gets SHA pinning because it is the one with a documented
    compromise. **First local run found real findings, triaged in full** (both `trivy fs` and
    `trivy image` now clean, verified locally against the actual built image): `trivy fs` started
    at 34 CRITICAL/HIGH, all in `spring-boot-starter-parent`-managed transitive deps — cleared to 6
    via patch-level bumps (`spring-boot-starter-parent` 3.3.4→3.3.13, plus explicit
    `postgresql.version`/`tomcat.version`/`netty.version` overrides to 42.7.11/10.1.55/
    4.1.135.Final — all same minor line, `mvn verify` re-confirmed green, 81/81 tests, after each
    bump). The remaining 6 (2 CRITICAL, 4 HIGH) all need a minor/major bump, outside this
    session's patch-level-only mandate — allowlisted in `.trivyignore` after checking THIS
    codebase's actual usage (not assumed): BouncyCastle GOST-cipher bug (unused, app only does
    ECDSA/Argon2), Spring Boot temp-dir session hijack (needs `session.persistent=true`, never set
    — sessions are Redis-backed), 2× Jackson PolymorphicTypeValidator bypass (no polymorphic
    typing configured anywhere), Spring-core `@EnableMethodSecurity` annotation gap (this app
    authorizes by route, not method annotations) — **except CVE-2026-22732** (Spring Security,
    CRITICAL, CVSS 9.1, header-writing bypass under the default lazy-write mode this app actually
    uses), whose reachability could NOT be ruled out; allowlisted but explicitly flagged for a
    dedicated follow-up (needs `spring-security-web` 6.5.9+ alone, a targeted minor bump — user
    decision, not silently deferred). `trivy image` additionally found 5 HIGH `golang.org/x/net`
    CVEs in `/usr/bin/pebble` — Canonical's container-init tool baked into the official
    `eclipse-temurin:*-jre` base image, confirmed via `docker inspect` to never run in this
    container (`ENTRYPOINT ["java","-jar","app.jar"]` only) — allowlisted as unreachable/
    unfixable-from-this-repo. **CVE-2026-22732 CLOSED (2026-07-18, session
    chore/swagger-and-flagged-fixes)** — see that session's entry below for the fix (whole
    `spring-security.version` line bumped to 6.5.9, not `spring-security-web` alone) and why the
    single-artifact path had to be abandoned; `.trivyignore` entry deleted, `trivy fs` passes
    clean without it. See `.trivyignore`'s remaining entries for every other allowlisted CVE's
    full justification.
  - **Part 2 — KH-0.3.4 (secrets)**: `gitleaks` CI gate (per-PR diff via `gitleaks-action@v2`,
    `fetch-depth: 0`). Full-history scan run locally: **0 real findings** — the 12 raw hits were one
    synthetic TEST fixture (`khatm-test-claims-enc-key-32byte`, base64) in two `src/test/java`
    files; allowlisted precisely in a new `.gitleaks.toml` (`useDefault = true` + regex allowlist
    for both the base64 and the decoded form). `.env.example` = the complete runtime env-var
    contract (required-outside-`local` vs local-default marked); `.gitignore` already covers
    `.env` / `*.p12` / secret paths (verified, no change).
  - **Part 3 — restore-from-zero proof (Phase-0 exit criterion 3)**: new `compose-smoke` CI job
    runs `scripts/smoke.sh`, the single "one command" — clean boot (api+worker+pg+redis), assert
    JWKS ≥1 key, login → issue → verify `valid:true` end-to-end (AdminBootstrap + session auth +
    SD-JWT signing/verify), `down -v`, boot again on the same image, re-assert. jq-free (sed/grep)
    for Git-Bash ↔ CI portability; CSRF handled (login → GET `/api/auth/me` forces the XSRF-TOKEN
    cookie → echoed as `X-XSRF-TOKEN` on `/issue`).
  - **Part 4 — KH-0.3.3 prep (inert until a host exists)**: new `release.yml` (push to `main`)
    builds + pushes the image to GHCR `ghcr.io/gloryms/khatm-platform` (`latest` + short SHA). The
    `deploy-staging` job SSHes + runs `docker compose pull && up -d`, gated on `STAGING_SSH_HOST`
    existing — it skips cleanly with a notice job when the secret is absent (no failure).
    `docs/deploy-staging.md` is the runbook (one-time host prep, exact secret names, activation =
    fill the secrets). Activation is explicitly a config task, not code.
  - See "Decisions made" → Session KH-0.3-closure.
- 2026-07-17: KH-0.6b — console auth, API keys, RBAC-lite & the full `audit_log` write path
  (spec FS-0.6b, D1–D10 as given; `mvn verify` green, 81/81 tests). **Completes Phase 0** — no
  endpoint ships without authentication behind it from here.
  - **New `rbac` module** (was a stub since KH-0.1.1): `api/` — `CurrentActor` +
    `CurrentActorResolver` (forward-looking; KH-1.4.3 is the first real consumer). `domain/` —
    `AppUser`/`Role`/`ApiKey` entities; `AuthService` (login, D6 Redis-TTL lockout, D7 one
    generic failure message for every reason); `ApiKeyService` (create/revoke/verify,
    `khk_<env>_<prefix>.<secret>`, SHA-256 of the secret per D4); `AdminBootstrap` (D10, same
    idempotent-`ApplicationRunner` shape as `key.domain.KeyBootstrap`). `security/` —
    `SecurityConfig`, `ApiKeyAuthFilter`, `ScopeGuard`, `KhatmAuthenticationEntryPoint`/
    `KhatmAccessDeniedHandler`, `SessionAuthenticator`, `CsrfCookieFilter`. `web/` —
    `AuthController` (login/logout/me + admin api-key create/revoke). `seed/` —
    `DemoApiKeySeeder` (local/dev only).
  - **New `shared/audit`** (`@NamedInterface("audit")`): `AuditService#record` is now the *only*
    way any module writes `audit_log` (D8) — enforced by a new architectural test,
    `NoDirectAuditLogInsertTest` (source-text scan, not ArchUnit — see below). `key.domain
    .KeyLifecycleService` (`KEY_CREATED`/`KEY_ROTATED`) and `credential.worker
    .ClaimCodeExpiryWorker` (`CLAIM_CODES_EXPIRED`) migrated off their KH-0.5/ADR-09
    direct-`JdbcTemplate`-insert stopgaps; `credential.domain.CredentialService` gained new
    `CREDENTIAL_ISSUED`/`CREDENTIAL_CONSUMED`/`CREDENTIAL_REVOKED` audit calls it never had
    before. Actor attribution crosses the `shared`→`rbac` boundary via a small SPI
    (`AuditPrincipal`), the same inversion `shared.events.StreamEventHandler` already uses.
  - **`V2__auth_api_keys.sql`** — the first post-baseline migration, exactly per spec §4: new
    `api_key` table (`owner_type ∈ {TENANT, CONSUMING_PARTY}`) + drops
    `consuming_party.api_key_hash`. `V1__baseline.sql` untouched; `MigrationImmutabilityTest`
    and the checksum lock file both green.
  - **`ErrorCode` gained `KH-RBC-0401`/`KH-RBC-1401`/`KH-RBC-0403`** — `AuthenticationException`/
    `AuthorizationException` (defined since KH-0.6a, unthrown until now) are finally exercised.
    `messages_en`/`messages_ar` + `docs/error-codes.md` updated in the same commit per the
    KH-0.6a work-rule-2/3 obligation.
  - **pom additions**: exactly the four approved — `spring-boot-starter-security`,
    `spring-session-data-redis`, `spring-security-test`, `bcprov-jdk18on` (Argon2's actual
    BouncyCastle dependency; `bcpkix-jdk18on` already pulled it transitively, pinned explicitly
    for clarity per D4).
  - **`application.yml`**: `khatm.auth.*` (session timeout, lockout, bootstrap admin — local
    profile document supplies the only permitted default, same no-silent-default pattern as
    `khatm.keys.soft.*`); `server.servlet.session.cookie.*` (name `KHATM_SESSION`, `http-only`,
    `same-site: lax`, `secure` true outside `local`); `spring.session.store-type: redis`.
  - **Tests** (new, beyond the ones already counted in 81/81): `rbac/` package — login/me/logout
    cycle, lockout + TTL recovery, `/issue` scope gate (401/403/200), `/consume` API-key gate
    (200 + audit / 401 revoked / 401 malformed / 403 session), admin api-key create/revoke,
    public-endpoints-zero-credentials, secrets-never-logged. `shared/audit/` —
    `NoDirectAuditLogInsertTest` (DoD-7), `AuditServiceTransactionalTest` (DoD-8, rollback +
    commit). `shared/events/WorkerProfileSecurityBootTest` (DoD-9 second half — worker profile
    boots with Security on the classpath; extends `WorkerRoleGuardTest`'s guard with a real
    full-context boot).
  - See "Decisions made" → Session KH-0.6b for the implementation-level interpretations code
    reality forced along the way (session ran long specifically because several of these only
    surfaced by actually running the test suite, not from reading the spec or framework docs
    alone) — none change the spec's stated functional behavior.
- 2026-07-16: ADR-09-WORKER — async worker skeleton + claim_code expiry zeroing (spec ADR-09 +
  FS-0.2 §3.7; `mvn verify` green, 62/62 tests).
  - **Externalizer decision (custom, not official)**: there is **no `spring-modulith-events-redis`
    for Modulith 1.2.x** (verified against the 1.2.4 source tree — only amqp/kafka/jms/aws-sqs/
    aws-sns ship). So `shared/events/RedisStreamsExternalizationConfig` provides the same shape
    those completions do: a `DelegatingEventExternalizer` bean whose `BiFunction` delegate `XADD`s
    each `@Externalized` event to its target stream, returns a completed `CompletableFuture`
    (normal → outbox row marked complete; failed → row stays incomplete for replay). All required
    event artifacts (`events-api`, `events-core`, `events-jackson`, `events-jdbc`) were ALREADY on
    the runtime classpath via `spring-modulith-starter-jdbc`; the one pom change is promoting
    **`spring-modulith-events-core` to compile scope** so `DelegatingEventExternalizer` is visible
    at compile time (it ships runtime-scoped under the starter).
  - **`api`/`worker` role split**: one `@SpringBootApplication`, role selected by Spring profile
    (compose already passes `local,api` / `local,worker`). `application.yml` `api`/`worker`/`test`
    profile documents set `khatm.role`/`khatm.web.enabled`/`khatm.worker.enabled`; the two business
    controllers (`CredentialController`, `JwksController`) are `@ConditionalOnProperty(web.enabled,
    matchIfMissing=true)` so they vanish in `worker`; the stream consumer + expiry sweep are
    `@ConditionalOnProperty(worker.enabled=true)`; `@EnableScheduling` on the main class (a no-op
    in `api` since no `@Scheduled` beans load); `RoleStartupLogger` logs the active role.
  - **`CredentialIssued` event** (`credential/events/`): `@Externalized("khatm.credential.events")`,
    proof-shaped payload `(ref, claimCodeExpiresAt, occurredAt)` — refs + timestamps only, never
    claims/disclosures (SEC §9). Published inside `CredentialService#issue`'s transaction; the JDBC
    outbox captures it, the externalizer ships it after commit. `claimCodeExpiresAt` is `null` for
    bare issuance (no claim code created there) — forward-looking field for a future consumer.
  - **Consumer infra** (`shared/events/`, `events` named interface exposed from `shared`):
    `WorkerStreamProperties` (`khatm.worker.stream.*`), `RedisStreamConsumer` (ensures the
    `khatm-workers` group, `@Scheduled` poll of `khatm.credential.events`), `StreamEventDispatcher`
    (idempotent by stream entry id via `khatm:processed:*` keys w/ TTL, synchronous retry up to
    `max-attempts` default 3, then dead-letter to `khatm.dlq` + ACK; `StreamEventHandler` SPI).
    `shared/events/README.md` documents the DLQ inspection commands (`XLEN`/`XRANGE`/`XREVRANGE`)
    and the no-automatic-requeue design.
  - **`ClaimCodeExpiryWorker`** (`credential/worker/`, worker-role only, `@Scheduled` default 5
    min): single bulk `UPDATE claim_code SET disclosures_enc=NULL WHERE expires_at<now AND
    disclosures_enc IS NOT NULL AND claimed_at IS NULL` (new `ClaimCodeRepository#zeroExpiredUnclaimed`
    JPQL `@Modifying` query), count logged, `CLAIM_CODES_EXPIRED` audit row written **only when
    count>0** (detail `{"count":N}`). This closes FS-0.2 §3.7's **expiry** half; the **on-claim**
    half belongs to the claim-delivery endpoint (KH-1.2.1).
  - **docker-compose**: unchanged — the `khatm-worker` service already passes `local,worker`, which
    now actually activates the consumer beans + disables business REST. Verified by inspection +
    the worker-role integration tests (no compose edit needed; the profile assumption was correct
    once the profiles meant something).
  - **Tests** (8 new): `WorkerRoleGuardTest` (7e — `ApplicationContextRunner`: worker=true loads
    consumer/dispatcher beans, worker=false/api loads none), `ClaimCodeExpirySweepTest` (7d — only
    expired-unclaimed zeroed; unexpired + already-claimed untouched; `CLAIM_CODES_EXPIRED` audit
    row with count; `disclosures_enc` NULL after ⇒ decrypt impossible), `RedisStreamWorkerTest`
    (7a outbox→stream→consumer round-trip + 7b idempotency), `RedisStreamDeadLetterTest` (7c — N
    failures → `khatm.dlq`, original ACKed/cleared). `NoDisclosureContentInLogsTest` extended with a
    sweep method (proves the sweep's logs never carry a claim value or salt).
  - **Side-fix (pre-existing, discovered)**: `docs/error-codes.md` had no `eol=lf` pin in
    `.gitattributes`, so a Windows CRLF checkout made `ErrorCodesDocGenerationTest`'s byte-for-byte
    comparison fail locally (passed on CI/Linux). Pinned `docs/error-codes.md text eol=lf` — the
    same fix CONVENTIONS §6 already applies to migrations/checksums. No content change to the file.
- 2026-07-16: KH-0.6a — Error hierarchy, envelope & EN/AR bundles (spec FS-0.6a, all eight
  pre-approved design decisions D1–D8 implemented as given; CLAUDE.md work rules 2 & 3 now LIVE).
  - **`shared/error/`** (new `@NamedInterface("error")`): `KhatmException` (abstract;
    constructor `(ErrorCode, messageKey, Object... args)` exactly as CLAUDE.md specifies) +
    six subtypes (`NotFoundException`, `ConflictException`, `ValidationException`,
    `IntegrityException` thrown today; `AuthenticationException`/`AuthorizationException`
    exist but stay unthrown until KH-0.6b, per spec §6). `ErrorCode` registry (D3:
    `KH-<MOD>-<NNNN>`, last 3 digits = HTTP status, leading digit = per-module-per-status
    sequence) — a deliberately **lean first batch**: `KH-CRD-0404`, `KH-KEY-0500`,
    `KH-SYS-0400` (generic Bean Validation failure), `KH-SYS-0500`. Omitted on purpose (task
    said "no speculative codes"): a schema-not-found code (nothing in the codebase can
    currently fail that lookup), a credential-conflict code (atomic-consume already returns
    its outcome as a 200 domain result). `VerifyReason` (D2): the separate, non-exception
    vocabulary for `/verify` domain results — migrated every KH-0.4 raw reason string, and
    split `unknown_kid` out from the old generic `bad_signature` (spec's own D2 vocabulary
    lists them separately; a missing/unresolvable `kid` is a materially different situation
    from a resolved key whose signature bytes don't verify). `grep` confirmed zero raw reason
    string literals remain outside `VerifyReason.java` itself.
  - **`shared/web/`** (new `@NamedInterface("web")`, exposing only `ErrorEnvelope`):
    `GlobalExceptionHandler` (`@RestControllerAdvice`) is the sole envelope producer —
    `KhatmException` family (WARN for 4xx, ERROR + full stack trace for 5xx),
    `MethodArgumentNotValidException` → `details[]` with `validation.<constraint>` keys,
    catch-all `Exception` → `KH-SYS-0500` generic message + full stack trace logged, nothing
    internal reaches the client. `TraceIdFilter` (`HIGHEST_PRECEDENCE`): accepts inbound
    `X-Request-Id` else generates a UUID, MDC + response header, removed in a `finally` (pooled
    threads). `docs/error-codes.md` generated from `ErrorCode` by `ErrorCodesDocGenerationTest`
    (D7) — same self-serve, fails-with-exact-fix-content philosophy as
    `MigrationImmutabilityTest`; a second test proves the comparison actually catches drift.
  - **i18n** (`shared/config/LocaleConfig`): `AcceptHeaderLocaleResolver`, `en` default,
    `en`/`ar` supported, anything else silently → `en` (D5 — Spring's built-in
    `setSupportedLocales` + `Locale.lookup` behavior does this with zero custom code).
    `MessageSource` explicit UTF-8 (`ReloadableResourceBundleMessageSource`). Bundles at
    `src/main/resources/i18n/messages_{en,ar}.properties` cover every `ErrorCode`/
    `VerifyReason` key plus `validation.NotBlank`. **`messages_ar.properties` needs the human
    Arabic-speaker review gate (spec §4) before this PR merges** — flagged in the PR body, not
    yet done as of this session ending.
  - **`MessageBundleParityTest`** (root test package, mirrors `ModulithBoundariesTest`'s
    location): bidirectional key parity, no blank values, every `ErrorCode`/`VerifyReason` key
    present, plus a direct assertion that `messages_ar.properties` values actually contain
    Arabic Unicode-block characters (catches silent mojibake, not just missing keys).
  - **Logging (D6)**: `logstash-logback-encoder:8.0` (not the newer 9.0 — its declared
    `logback-classic` baseline, 1.5.6, sits safely under Spring Boot 3.3.4's managed 1.5.8;
    9.0 wants 1.5.20, which isn't available). `logback-spring.xml`: JSON in every profile
    except `local` (confirmed empirically — the `test` profile's actual console output during
    this session's own `mvn verify` run was real JSON, not just asserted by a test).
    `NoDisclosureContentInLogsTest` (KH-0.4) stays green over the new encoder untouched, since
    it captures `ILoggingEvent` objects via `ListAppender`, upstream of any encoder.
  - **`CredentialService`**: `#issue` wraps a `KeySigner` `JOSEException` as
    `IntegrityException(KH-KEY-0500)` instead of propagating a checked exception — drops
    `throws JOSEException` entirely, letting `CredentialController.issue` drop `throws
    Exception` too (the specific offender the task named). `#verify`'s `checkSignature` helper
    (renamed from `hasValidSignature`) now returns `VerifyReason` instead of `boolean`, doing
    the `unknown_kid`/`bad_signature` split. `VerifyResponse` gained `reasonMessage` — resolved
    in `CredentialController` (not the domain service, which stays i18n-free) via
    `MessageSource` + `LocaleContextHolder.getLocale()`. `CredentialController#get`/`#revoke`
    throw `NotFoundException` instead of hand-building `ResponseEntity.notFound()`.
    `IssueRequest.holderRef` and `VerifyRequest.sdJwt` gained `@NotBlank` (+ `@Valid` on the
    controller params) — the concrete Bean Validation path DoD #3 exercises.
  - **`schema :: api`**: unchanged from KH-0.4 — no new dependency needed for any of this.
  - **OpenAPI**: `ErrorEnvelope`/`ErrorDetail` referenced as the shared error-response schema
    from `/issue` and `/verify`'s existing annotations only (task scope) — full coverage of
    every endpoint + CI-published `openapi.json` stay KH-1.6.
  - **Tests**: `ErrorEnvelopeAndI18nTest` (new, own `RANDOM_PORT` + Testcontainers base —
    `IntegrationTestSupport` deliberately pins `WebEnvironment.NONE`) covers DoD #1 (404 +
    synthetic 500 via a test-only, `@Profile("test")`-gated `TestBoomController` — never
    shipped, lives under `src/test/java`) with identical envelope shape, #2 (Arabic assertion +
    unsupported-language silent fallback), #3 (Bean Validation `details[]`), #4 (`/verify` on a
    tampered disclosure in both languages), #5 (same traceId across response header, envelope
    body, and captured log lines for one request, plus UUID generation when no header sent).
    `JsonLogEncodingTest` (DoD #8) encodes a real captured log event with the actual
    `LogstashEncoder` class the logback config uses and parses the result as JSON.
  - Fixed `CredentialSigningAndVerificationTest` (KH-0.5): its "outside-registry key" scenario
    now correctly asserts `unknown_kid`, not the old generic `bad_signature`.
- 2026-07-16: KH-0.4.1 + KH-0.4.2 + KH-0.4.3 — SD-JWT signing upgrade (spec FS-0.4, all eight
  pre-approved design decisions D1–D8 implemented as given).
  - **Library confirmed before adopting (D4 gate)**: `com.authlete:sd-jwt:1.9` — read its
    actual source on GitHub (not just the README) to verify it never touches signing/key
    material. Confirmed: `SDObjectBuilder`/`Disclosure`/`SDJWT` only build/parse the payload
    Map and disclosure strings; signing stays exclusively through `KeySigner` (unchanged, D4).
    Its own `_sd` digest-list builder already sorts alphanumerically — satisfies D5's
    "(shuffled)" requirement for free, no manual shuffling needed. Its default `Disclosure`
    salt generation is already `SecureRandom`, 128-bit, base64url — satisfies D5's salt
    requirement exactly, verified by reading `SDUtility.generateRandomBytes`/`Disclosure`
    source directly rather than trusting the README's word for it.
  - **`CredentialService#issue`**: every `claims` entry → `SDObjectBuilder.putSDClaim` (D1);
    only D3's structural fields (`iss`, `iat`, `nbf`, `exp`, `vct` = `{schema.code}:{version}`,
    `ref`, `status`) added via `putClaim`; `status` follows the IETF Token Status List shape
    (`status.status_list.{idx,uri}` — `uri` is a provisional placeholder, the raw
    `status_list_id`, until KH-1.3 publishes a real signed-bitstring endpoint, spec §7).
    `JWTClaimsSet.parse(Map)` converts the built payload Map into what `KeySigner.sign()`
    still takes unchanged. `credential.signed_payload` stores the compact JWT only (D6); the
    response returns the full tilde-separated presentation (`IssueResponse.sdJwt`, replacing
    the old `jwt` field per work rule 4 — not kept alongside it).
  - **`CredentialService#verify`**: accepts the tilde presentation, or a bare compact JWT
    treated as a zero-disclosure presentation (spec §5 — not an error). Existing sig/exp/ref
    /revoked checks unchanged; added `_sd_alg == "sha-256"` check, per-disclosure digest +
    duplicate-name checks (`forged_disclosure`/`duplicate_disclosure`), and the D2
    mandatory-disclosure check (every `claims_def` field not in `sd_fields` must be
    disclosed, else `withheld_mandatory_claim`) via `SchemaCatalog#findById` — no new
    cross-module dependency, `schema :: api` was already depended on.
  - **`ClaimsEncryptionService`** (new, `credential.domain`, module-private): AES-256-GCM, key
    from `khatm.claims.enc-key` (32-byte base64 env var), random 96-bit nonce per call
    prepended to the ciphertext, fail-fast startup outside `local` — mirrors
    `SoftKeyProvider`'s passphrase pattern exactly (constructor check + `application.yml`
    profile document + dedicated fail-fast test). `CredentialService#issueClaimCode` now
    actually encrypts `join(disclosures, "~")` into `disclosures_enc` (D7) — closes the
    encryption half of the long-open `disclosures_enc` blocker (see below).
  - **`schema :: api` widened**: `SchemaRef` gained `claimsDefJson` + `sdFields` (previously
    id/code/version only) — the verify path's mandatory-disclosure check needs the full
    field list. No new module boundary; `credential` already depended on `schema :: api`.
  - **`DemoSeeder`**: demo schema now has a real mandatory/optional split (`result` mandatory;
    `caseNumber`/`issuedAt` withholdable) so both directions are exercised by construction.
  - **OpenAPI**: `springdoc-openapi-starter-webmvc-api:2.8.17` added (the last release in the
    2.x line — matches Spring Boot 3.3.x; the 3.x line targets Boot 3.4+). Deliberately the
    "-api" artifact, not "-webmvc-ui" — JSON generation (`/v3/api-docs`) and annotations only,
    no live Swagger UI exposed (no auth exists ahead of KH-0.6). Only `/issue` and `/verify`
    annotated (this session's actual scope); full endpoint coverage and CI-published
    `openapi.json` remain KH-1.6 (spec FS-0.4 §7 names it explicitly).
  - **Message bundles**: still don't exist (`messages_en/ar.properties` — KH-0.6, unchanged
    from KH-0.5's note). No new user-facing message keys were introduced this session either,
    so there was nothing to add even if the bundles existed.
  - **Tests**: `SdJwtIssuanceStructuralTest` (DoD #1, flagship — decodes the *persisted*
    `signed_payload` row and asserts no `claims_def` key/value appears anywhere, only D3
    fields + `_sd`/`_sd_alg`), `SdJwtVerificationTest` (DoD #2 round-trip, #3 selective
    disclosure, #4's four rejections — tampered value, forged digest, duplicate, withheld
    mandatory — plus the zero-disclosure-presentation case), `ClaimsEncryptionServiceTest` +
    `ClaimsEncryptionKeyFailureTest` (DoD #5), `NoDisclosureContentInLogsTest` (DoD #7 — a
    Logback `ListAppender` captures a full issue→verify→claim-code cycle and asserts no
    plaintext claim value or salt appears in any log line). DoD #6 (FS-0.5 key-module tests
    unmodified and green) confirmed by running that suite untouched — all 23 pass.
- 2026-07-15: KH-0.5.1 + KH-0.5.2 + KH-0.5.3 — Key Provider SPI & SoftKeyProvider (spec
  FS-0.5, all four pre-approved design decisions D1–D4 implemented as given).
  - **`key :: api`** unchanged surface, new shape: `KeySigner.sign()` now returns `SignResult`
    (`kid`/`algo`/`jws`) instead of a bare `String`; new `KeyVerifier.resolvePublicKey(kid)` →
    `Optional<PublicKeyHandle>`, resolving strictly by `kid` with no fallback (SEC §3, spec §4).
  - **`key/domain/`** (all module-private): `KeyProvider` — a deliberately crypto-only SPI
    (`generate`/`sign`/`publicKey` against an opaque `providerRef`), scoped this way (not the
    tenant/DB-aware "full SEC §3 contract" shape literally) so a future `KmsProvider` never
    needs to know about `issuer_key` rows or lifecycle states (D3). `SoftKeyProvider` — the only
    implementation today: one PKCS#12 keystore file, alias == `kid`, selected via
    `@ConditionalOnProperty(khatm.keys.provider=SOFT, matchIfMissing=true)`. Mints a throwaway
    self-signed X.509 cert per key (via `bcpkix-jdk18on`, new `pom.xml` dependency) purely to
    satisfy `KeyStore.setKeyEntry`'s chain requirement — verification never uses the cert chain,
    only the raw EC public key. `KeyLifecycleService` — owns `issuer_key` persistence, the
    `PENDING→ACTIVE→RETIRING→RETIRED` state machine, and the one-`ACTIVE` invariant; `rotate()`
    is fully implemented (no REST endpoint — tests only, per spec) and writes `KEY_CREATED` /
    `KEY_ROTATED` `audit_log` rows via a direct `JdbcTemplate` insert (minimal form; full audit
    write path is KH-0.6). `KeyBootstrap` — idempotent `ApplicationRunner`, all profiles.
  - **`key/web/JwksController`** replaces the old `WellKnownController`:
    `GET /.well-known/jwks.json` only (the old `/.well-known/pubkey.pem` endpoint is gone — not
    in FS-0.5's scope, and the old `KeySigner.publicKeyPem()` method it depended on no longer
    exists), `ACTIVE`+`RETIRING` public keys, `Cache-Control: max-age=300`, no auth.
  - **`SoftKeyService` deleted in full** (D4 — no `@Deprecated` shim); `CredentialService`
    rewired to the new `KeySigner`/`KeyVerifier` contracts (added `KeyVerifier` constructor
    dependency; `verify()` now resolves the JWT's `kid` header through it and checks the
    signature manually with a Nimbus `ECDSAVerifier` — no other module needed to change).
  - **`shared.TenantContext`** gained `DEFAULT_TENANT_SLUG`/`currentSlug()` (mirrors the
    existing `DEFAULT_TENANT_ID`/`current()` pair) — lets `key` build `kid` values
    (`{tenant-slug}:key-{seq}`) without a cross-module dependency on `tenant`, which has no
    `api` sub-package yet.
  - **`IssuerKeyRepository.retireActive`**: a `@Modifying` JPQL bulk `UPDATE` (not a plain
    entity save) — deliberately runs immediately rather than being deferred to Hibernate's
    flush-time ordering (which flushes pending inserts before pending updates), so `rotate()`'s
    old-key-to-`RETIRING` transition is guaranteed to commit at the database *before* the new
    key is inserted as `ACTIVE`. Without this, the `issuer_key_one_active` partial unique index
    could see two `ACTIVE` rows momentarily and reject the insert.
  - **`application.yml`**: new `khatm.keys.*` surface per spec §7. The base document leaves
    `khatm.keys.soft.passphrase` with no default (`${KHATM_KEYS_PASSPHRASE:}`); a second
    `spring.config.activate.on-profile: local` document supplies the only permitted default.
    `SoftKeyProvider`'s constructor fails startup immediately if the passphrase is blank and the
    `local` profile isn't active — verified by test, not just by inspection.
  - **`docker-compose.yml`**: named volume `khatm_keys` mounted at `/var/khatm/keys` on both
    `khatm-api` and `khatm-worker` (same file, both roles), plus `KHATM_KEYS_PASSPHRASE` env
    (same local-only default as `application.yml`'s `local` profile document). `khatm-deploy`
    (separate repo) intentionally untouched.
  - **Tests** (`src/test/java/sy/khatm/platform/key/**`, plus one in `credential/domain/`):
    `KeyLifecycleServiceTest` (bootstrap idempotency, `rotate()`'s one-active invariant +
    JWKS-shows-both + old-signature-still-verifies + new-kid, unknown/`RETIRED` kid rejection,
    no private material in `public_jwk`, both audit rows): 7 tests, all against the shared
    Testcontainers context. `KeyProviderRestartPersistenceTest` — the criterion-2 test: two
    fully independent `SpringApplicationBuilder` runs (own dedicated Postgres container, real
    `.run()`/`.close()` cycle) against the *same* keystore file, proving a signature from the
    first run still verifies under the same `kid` after the second. `SoftKeyProviderPassphraseFailureTest`
    — wrong passphrase on an existing keystore, and missing passphrase outside `local`, both
    fail startup with a message traceable to "passphrase," and the file is never overwritten.
    `JwksControllerTest` — plain Mockito unit test (no Spring context) for the HTTP response
    shape/headers. `CredentialSigningAndVerificationTest` — `kid` format through the real
    issuance path, and a JWT signed by a key outside the registry rejected as `bad_signature`.
  - **`IntegrationTestSupport`** (shared test base) gained its own `khatm.keys.soft.*`
    `@DynamicPropertySource` (one temp keystore file for the whole shared-context suite) —
    every pre-existing integration test runs under the `test` profile, not `local`, so without
    this every one of them would have failed `SoftKeyProvider`'s new fail-fast passphrase check.
  - **Toolchain note**: this session's `mvn verify` required `JAVA_HOME` pointed at the
    Eclipse Temurin 21 install (`environment.md` memory had drifted back to JDK 17 — fixed).
- 2026-07-15: Housekeeping — spec-directory reconciliation. Root `specs/` was a manual-copy
  mistake; `docs/specs/` is the canonical location per CLAUDE.md. Both `FS-0.2` and `FS-0.5`
  were byte-identical in both locations, so the root copies were `git mv`-removed and the
  now-empty `specs/` directory deleted; `docs/CONVENTIONS.md` §9 gained a line stating
  `docs/specs/` is the only approved spec location.
- 2026-07-14: KH-0.3.1 — GitHub Actions CI pipeline
  - `.github/workflows/ci.yml`: triggers on `pull_request` into `main` and `push` to `main`.
    Fail-fast step order: `scripts/check-migration-checksums.sh` (cheap, no JVM) →
    `actions/setup-java@v4` (Temurin JDK 21, `cache: maven`) → `mvn -B verify` (Spotless,
    Checkstyle, Modulith boundaries, all tests). No deploy steps (KH-0.3.3 is out of scope).
  - **Confirmed the `src/test/resources/docker-java.properties` (`api.version=1.44`) pin from
    KH-0.2.1 does NOT break `ubuntu-latest` runners** — Testcontainers-backed tests passed in
    CI on the first run with no changes needed. That pin was specifically for local Docker
    Desktop/Windows quirks; GitHub-hosted runners' native Docker Engine negotiates the pinned
    API version fine. No conditional logic added — none was needed.
  - First CI run on this task's own PR (#4) went green end-to-end in 1m31s — verified via
    `gh run watch` and `gh pr checks`, not just "should work."
  - Minimal repo-root `README.md` added (one paragraph + CI badge); full README deferred.
  - `docs/CONVENTIONS.md` §10 gains "CI must be green before merge."
  - `.gitattributes`: extended `eol=lf` pinning to `*.yml`/`*.yaml` (same rationale as
    KH-0.2.2's `*.sql`/`*.sh`/`*.lock` rule) and normalized the two YAML files that had
    drifted to CRLF in the working tree.
- 2026-07-14: KH-0.2.2 — append-only migration discipline
  - **Local/build-time guard**: `db/migration-checksums.lock` (repo root; `<filename>\t<sha256>`
    per line) + `MigrationImmutabilityTest`
    (`src/test/java/sy/khatm/platform/db/MigrationImmutabilityTest.java`, no Spring context —
    pure file I/O, stays fast). Recomputes every migration's SHA-256 on every build; fails on
    a checksum mismatch (edited), a locked file that's gone (deleted), or a migration file
    with no lock entry (added without registering it). The UNREGISTERED failure message
    prints the exact line to paste into the lock file. All three failure paths manually
    verified by temporarily corrupting the lock file / adding an unregistered file / removing
    a locked file and confirming the expected message, then restoring.
  - **`.gitattributes` added** (`*.sql`, `*.sh`, `*.lock` → `eol=lf`): the migration file was
    CRLF in the working tree (Windows `core.autocrlf=true`) while the git blob was already LF
    — without pinning this, a future Linux CI checkout would see different bytes than this
    Windows session hashed, and the very first CI run would falsely report `V1__baseline.sql`
    as "modified." Renormalized `V1__baseline.sql` to LF on disk to match.
  - **CI-prep layer**: `scripts/check-migration-checksums.sh` — standalone bash
    re-implementation of the same three checks (no JVM needed), executable bit tracked in git
    (`100755`), ready for KH-0.3.1 to invoke as a pipeline step. GitHub Actions workflow itself
    is explicitly KH-0.3.1's scope, not built here.
  - **Runtime layer**: `spring.flyway.validate-on-migrate: true` made explicit in
    `application.yml` (was already Flyway's default) — catches drift against a real
    database's `flyway_schema_history`, independent of the build-time checksum check.
  - `docs/CONVENTIONS.md` gains `## 6. Migrations are append-only`; sections 6–9 renumbered to
    7–10 to make room (Async, Tests, Documentation, Commits & PRs), including a `§7`→`§8`
    cross-reference fix. `MigrationImmutabilityTest` added to §8's mandatory named tests list.
- 2026-07-14: Housekeeping (approved architecture-review decisions, no WBS feature work)
  - Rebased `feat/KH-0.2.1-baseline-schema` onto updated `main` (KH-0.1.1 merged via PR #1).
    Trivial — git recognized `3713499` was already incorporated as squash commit `dfde818`
    and skipped it, replaying only the KH-0.2.1 commit. Zero conflicts.
  - Upgraded toolchain to Java 21: `pom.xml` `java.version` + `maven.compiler.release` both
    `21`. Installed Eclipse Temurin 21.0.11 (`winget install EclipseAdoptium.Temurin.21.JDK`).
    Verified with `mvn clean verify` under `JAVA_HOME` pointed at the new JDK: BUILD SUCCESS,
    8/8 tests, and confirmed compiled class files are major version 65 (Java 21) via `javap`.
    No Java 18–21 language features adopted in existing code (toolchain-only change).
  - Added `@org.springframework.modulith.ApplicationModule` to the 8 modules that lacked it
    (tenant, schema, status, ledger, holder, consumer, rbac, connector) — all 11 modules now
    carry the annotation consistently. `ModulithBoundariesTest` stays green.
  - Extracted `CredentialService#toView` into a new `CredentialMapper` class
    (`credential/domain/CredentialMapper.java`, module-private, injects `SchemaCatalog` to
    resolve `schemaCode`) per CONVENTIONS.md §5's manual-mapper-class rule. No behavior
    change — same 8/8 tests green.
  - `docs/STATE.md`: split "Decisions made" into per-session subsections, deleted the stale
    "`ddl-auto: update` kept" line (false since KH-0.2.1), moved 3 durable conventions to
    `docs/CONVENTIONS.md` (entity visibility, Checkstyle logger/MethodName exceptions, new
    spring-modulith-upgrade DDL-diff rule).
  - `docs/CONVENTIONS.md`: added the 3 moved conventions (§2, §5, §6) plus a new PR rule
    (§9) requiring same-PR concurrency/correctness tests for core invariant logic changes,
    effective 2026-07-13.
  - `specs/FS-0.2-database-baseline.md` status header updated to note §5.7 (error-codes.md)
    is partially deferred to KH-0.6 — approved 2026-07-13.
  - Inspected `claim_code.disclosures_enc`: confirmed unencrypted (in fact entirely unset,
    not just plaintext) — logged as an open blocker below rather than implemented, per
    instructions.
- 2026-07-13: KH-0.2.1 — Flyway V1__baseline enterprise schema
  - `src/main/resources/db/migration/V1__baseline.sql`: all 13 business tables from
    `specs/FS-0.2-database-baseline.md` (tenant, issuer_key, credential_schema, holder,
    status_list, credential, claim_code, consuming_party(+_schema), consumption_event,
    app_user, role, user_role, audit_log) + Spring Modulith's official `event_publication`
    schema (copied verbatim from `spring-modulith-events-jdbc:1.2.4`). Seeds the default
    tenant + 3 default roles (PLATFORM_ADMIN, TENANT_ADMIN, ISSUER_OPERATOR).
  - `ddl-auto: validate` is now live; Flyway (`flyway-core` + `flyway-database-postgresql`
    + `spring-modulith-starter-jdbc`) is the only schema source.
  - New shared infra: `LocalizedText`/`LocalizedTextConverter` (the one JPA converter for
    every `name_i18n`/`label_i18n` jsonb column), `TenantContext` (fixed default-tenant UUID
    until KH-2.1), `Uuidv7` (D1: app-generated, time-ordered PKs — replaces
    `UUID.randomUUID()` everywhere).
  - Minimal persistence + one find-or-create cross-module method added to 4 previously-stub
    modules: `schema` (`SchemaCatalog#ensurePublished/#findById`), `holder`
    (`HolderDirectory#ensureHolder`), `status` (`StatusListAllocator#allocate`,
    pessimistic-lock based — no `RETURNING`-without-`@Modifying` trick, see decisions below),
    `consumer` (`ConsumingPartyRegistry#ensure`). `tenant` gets a `Tenant` entity + repo only
    (no API — see `TenantContext` above). `rbac` stays a pure stub (tables + seed rows only,
    no Java).
  - `credential` module rewritten to match the new schema end to end: `Credential`,
    `ConsumptionEvent` entities now carry FKs (`schema_id`, `holder_id`, `status_list_id`,
    `consuming_party_id`) instead of denormalized strings; new `ClaimCode` entity/table;
    `CredentialService#issue`/`#consume` orchestrate schema/holder/status/consumer APIs;
    `#issueClaimCode` added. `DemoSeeder` now issues a full document (schema + holder +
    credential + claim_code).
  - 8 tests, all green: `MigrationCleanBootTest`, `DemoSeederIntegrationTest`,
    `ConcurrentConsumeTest` (50 threads, exactly 1 success), `ConsumptionEventIdempotencyTest`
    (duplicate idempotency_key → unique violation), `AuditLogAppendOnlyTest` (UPDATE/DELETE
    rejected by trigger), `TenantNameI18nCheckTest` (missing `ar` → CHECK violation),
    `ModulithBoundariesTest`. All use a Testcontainers Postgres via a singleton-container base
    class (`support/IntegrationTestSupport`) so the suite boots the app once.
  - README.md added to all 11 modules (Work rule 1).
- 2026-07-13: KH-0.1.1 + KH-0.1.2 — Modulith restructure + boundary verification
  - Package layout migrated from `sy.khatm.poc.*` → `sy.khatm.platform.*`
  - All 11 modules created with `package-info.java` (tenant, key, schema, credential,
    status, ledger, holder, consumer, rbac, connector, shared)
  - Spring Modulith 1.2.4 BOM + `spring-modulith-starter-core` + `spring-modulith-starter-test`
  - `ModulithBoundariesTest` (pure bytecode analysis, no DB) — GREEN
  - Spotless 2.43.0 (google-java-format 1.22) — GREEN
  - Checkstyle 3.3.1 — GREEN (custom `checkstyle.xml`)
  - `.editorconfig` added
  - `pom.xml` renamed to `khatm-platform`, Spring Boot 3.3.4 retained

## Decisions made

### Session KH-0.1.1 (2026-07-13)
- **Cross-module key access**: `credential` module depends on `key :: api` (the `KeySigner`
  interface in `key/api/`). `SoftKeyService` in `key/domain/` is module-private.
  Named-interface mechanism: `@NamedInterface("api")` on `key/api/package-info.java`.
- **`credential/api/` named interface**: `@NamedInterface("api")` exposes only DTO records.
  `Credential` entity, `CredentialService`, repositories are module-private (in sub-packages).
- **DemoSeeder placement**: inside `credential/seed/` module, `@Profile({"local","dev"})`.
  Depends directly on `CredentialService` (same module — no API interface needed).

### Session KH-0.2.1 (2026-07-13)
- **UUIDv7 everywhere**: added `shared.Uuidv7` per spec D1; every entity's `id` is now
  app-generated UUIDv7, not `UUID.randomUUID()` (v4).
- **Status-list allocation is pessimistic-lock, not `UPDATE...RETURNING`**: first attempt used
  a native `@Query` with `UPDATE ... RETURNING` and no `@Modifying`, which is a
  known-fragile Spring Data JPA pattern. Switched to `@Lock(PESSIMISTIC_WRITE)` on the finder
  + plain read-increment-save in `StatusListAllocatorService` — same atomicity guarantee,
  standard Spring Data JPA.
- **Cross-module credential dependencies expanded**: `credential/package-info.java`
  `allowedDependencies` grew from `{key :: api}` to `{key :: api, schema :: api, holder ::
  api, status :: api, consumer :: api, shared}` — issuing/consuming now must resolve every FK
  the baseline schema requires. `shared` is listed by module name alone (no `::`) because
  `LocalizedText`/`TenantContext`/`Uuidv7` live in its open root package, not a named `api`
  sub-package.
- **`ConsumingPartyRegistry#ensure`, `SchemaCatalog#ensurePublished`, `HolderDirectory
  #ensureHolder` are find-or-create, not real onboarding**: real API-key issuance (KH-1.4.3),
  schema authoring (KH-1.x), and holder registration UX (KH-1.x) remain future work. These
  exist only so the baseline schema's `NOT NULL` FKs can be satisfied today.
- **Testcontainers + Docker Desktop 4.58 / Engine 29 compatibility**: Testcontainers
  1.20.1's default docker-java client negotiates an API version too old for Engine 29
  (`MinAPIVersion: 1.44`), which Docker Desktop rejects with an empty-bodied HTTP 400
  instead of a clear error. Fixed via `src/test/resources/docker-java.properties`
  (`api.version=1.44`) — see testcontainers-java issue #11235. If `mvn verify` fails locally
  with "Could not find a valid Docker environment" and `docker info` works fine from a shell,
  this is almost certainly the cause.
- **`docs/error-codes.md` / `ErrorCode` enum NOT created this session**: CLAUDE.md work rule 3
  (exception hierarchy, error envelope) is out of scope for KH-0.2.1 — it's a dedicated future
  task. `shared/README.md` notes this explicitly so it isn't mistaken for an oversight.

### Session Housekeeping (2026-07-14)
- **Java 21, toolchain-only**: bumped per an approved architecture-review decision made
  2026-07-13. **`CLAUDE.md`'s "Stack (frozen)" line still says "Java 17"** — this session was
  scoped to exactly the items requested and did not include editing `CLAUDE.md`; whoever
  reviews this should update that line too, or it will read as a stale contradiction.
- **`CredentialMapper` needs `SchemaCatalog`, not a pure entity→DTO function**: `Credential`
  only stores `schema_id`; `CredentialView` needs the human-readable `schemaCode`. The mapper
  is a `@Component` with constructor-injected `SchemaCatalog`, not a static utility — still
  matches CONVENTIONS.md §5's intent (a dedicated class, not inline mapping in the service).
- **`@ApplicationModule` added with no `allowedDependencies` on the 8 newly-annotated
  modules**: they have no cross-module dependencies today, so the bare annotation is
  sufficient; it only documents the module boundary, it doesn't change enforcement (Modulith
  already treated these as modules structurally, annotated or not).

### Session KH-0.2.2 (2026-07-14)
- **Lock file lives at repo-root `db/migration-checksums.lock`, not
  `src/main/resources/db/`**: it has no runtime purpose — only tests/CI read it — so it
  shouldn't be bundled into the deployable JAR the way `src/main/resources` contents are.
- **UNREGISTERED (new, unlisted migration) is a hard build failure, not a warning**: the task
  explicitly asked for the failure message to say "adding a new migration requires adding its
  checksum line" — this only self-serves future sessions if it's impossible to miss, i.e. the
  build actually fails until the line is added.
- **Chose a standalone bash script over a dedicated Maven plugin binding for the CI-prep
  layer**: `MigrationImmutabilityTest` already gives Maven-verify-time enforcement for free
  (it's a normal Surefire test); the script's job is specifically to be invocable *without* a
  JVM/Maven bootstrap, which is what makes it a cheap early CI step later.

### Session KH-0.3.1 (2026-07-14)
- **Single job, not split into separate "checksum" / "build" jobs**: splitting would add
  GitHub Actions job-startup overhead (each job gets its own fresh VM) for no real benefit —
  the checksum step already runs first within the one job and fails the whole run immediately
  if it fails, which is all "fail-fast ordering" required.
- **No `concurrency` group / run cancellation, no caching beyond `actions/setup-java`'s
  built-in `cache: maven`**: kept the workflow to exactly what the task asked for; nice-to-have
  CI ergonomics (auto-cancel superseded runs, etc.) can be added later without needing to
  revisit this decision.
- **Verified the Docker Desktop `api.version=1.44` pin against a real runner instead of
  reasoning about it**: Docker's API is backward-compatible so it was likely fine, but "likely
  fine" isn't the same as confirmed — the task asked to confirm, so the first PR's CI run is
  the actual evidence, not an assumption.

### Session KH-0.5 (2026-07-15)
- **`KeyProvider` scoped to pure crypto, not literally SEC §3's four-method
  sign/publicJwks/rotate/keys shape**: the spec diagram lists `KeyProvider` as "the complete
  SPI (sign / publicJwks / rotate / keys)," but giving the swappable interface DB/tenant/
  lifecycle awareness would mean a future `KmsProvider` has to know about `issuer_key` rows and
  the state machine — the opposite of D3's promise ("swap provider = config change, zero
  code"). Split instead: `KeyProvider` = generate/sign/publicKey against an opaque
  `providerRef`; `KeyLifecycleService` = everything DB/tenant/state-machine, calling into
  whichever `KeyProvider` is active. D1–D4 as literally stated are unaffected — this is an
  internal domain-layer split, invisible outside `key/domain/`.
- **`KeyLifecycleService` (and `PublishedKey`) are `public` Java classes despite being
  Modulith-module-private**: `key/web/JwksController` is a different Java package from
  `key/domain/`, so package-private (the default) would make it uncompilable. Same precedent
  CONVENTIONS.md §5 already documents for JPA entities — Java visibility can't express
  Modulith module-privacy; `ModulithBoundariesTest`'s package-based analysis is what actually
  enforces the boundary, not `public`/package-private.
- **No REST endpoint for `rotate()`, by design (matches the approved D-decisions, not a gap)**:
  spec FS-0.5 §5 is explicit that admin-triggered rotation is KH-2.2 (needs RBAC to gate it).
  `KeyLifecycleService.rotate()` is `@Transactional` and fully correct today; it's exercised
  only by tests until then.
- **`KeyBootstrap` runs in every profile, not just `local`/`dev`**: unlike `DemoSeeder`, a
  production boot with zero issuer keys is a broken deployment, not a missing convenience —
  there is no other provisioning path yet (explicitly temporary; see the module README).
  **Superseded (2026-07-18, session chore/swagger-and-flagged-fixes)**: "every profile" turned out
  to include every *role* too (`api` and `worker` both ran it), which was a real concurrency bug,
  not just an unused-elsewhere no-op — see that session's entry for the fix. It still runs in
  every *profile* (local/dev/prod alike), just only in the `api` role now.
- **Command-line-style `--key=value` args, not `.properties(...)`, for the two
  multi-`SpringApplicationBuilder`-run tests**: `SpringApplicationBuilder.properties(String...)`
  registers a *lowest-precedence* "defaultProperties" source — `application.yml`'s own
  `spring.datasource.url` entry (with its `${SPRING_DATASOURCE_URL:localhost:5432}` fallback)
  wins over it every time, so the override was silently ignored and the second/third context in
  each test tried to reach a real `localhost:5432` (refused). `.run("--key=value", ...)` args
  have near-top precedence and actually override the yml. `@DynamicPropertySource` (used by
  `IntegrationTestSupport`) doesn't have this problem — it operates at a different layer
  (`ContextCustomizer`) that always wins regardless.

### Session KH-0.4 (2026-07-16)
- **`status` claim follows the IETF Token Status List draft shape
  (`status.status_list.{idx,uri}`), not a flatter `status.{idx,list}`**: spec D3's Arabic
  gloss ("status_list: list URL + idx") reads naturally as naming the real IETF field
  (`status_list`) containing `uri`+`idx` — matching a real spec beats inventing a bespoke
  shape, and it costs nothing extra now. `uri` is a placeholder (the raw `status_list_id`) —
  no real bitstring endpoint exists before KH-1.3, so there is nothing to point it at yet.
- **`IssueRequest` gained `sdFields` rather than inventing a schema-authoring path**: real
  schema authoring (mandatory vs. optional claims_def fields, typed editor) is KH-1.x and out
  of scope. `DemoSeeder`/any dynamic-schema caller needed *some* way to express "these fields
  are optional" for D2 to be exercisable at all; a nullable `List<String>` request field
  (null → everything withholdable, preserving old single-caller behavior) was the smallest
  change that didn't touch the schema module's actual authoring model.
- **`SchemaRef` widened (`claimsDefJson`, `sdFields`) instead of adding a new schema-module
  method**: the verify path's mandatory-disclosure check (D2) needs the full claims_def field
  list, and `credential` already depends on `schema :: api` — widening the existing DTO some
  callers already hold needs no new cross-module dependency and no new boundary for
  `ModulithBoundariesTest` to police.
- **`issueClaimCode` now takes the `sdJwt` presentation as a parameter, not just the
  credential id**: disclosures are never persisted anywhere in plaintext (by design, P1-
  adjacent), so a later, independent call has no other way to reach them. The only holder of
  the plaintext disclosures at any point after `issue()` returns is whoever received the
  `IssueResponse` — so encryption has to happen from that same handoff, not from a separate
  DB-only lookup. `DemoSeeder` was updated to pass `issued.sdJwt()` through immediately.
- **`springdoc-openapi-starter-webmvc-api` (no UI), 2.8.17 not 3.0.3**: CLAUDE.md's frozen
  stack already names springdoc-openapi, so adding the dependency itself isn't a new decision
  — but *how much* of it to add is: the "-api" artifact gives annotations + `/v3/api-docs`
  JSON generation without exposing a live Swagger UI, which felt premature with zero auth in
  front of anything before KH-0.6. Version 2.8.17 (not the newer 3.0.x line) because 3.x
  targets Spring Boot 3.4+/Spring Framework 6.2+ and this project is pinned to Boot 3.3.4.
- **Only `/issue` and `/verify` got OpenAPI annotations, not every endpoint**: the task scope
  was "the changed issue/verify request-response shapes" specifically; retroactively
  annotating `/consume`, `/revoke`, `/{id}` (unchanged this session) would have been scope
  creep beyond what was asked, and full coverage + CI publishing is explicitly KH-1.6 per
  spec FS-0.4 §7.

### Session KH-0.6a (2026-07-16)
- **ErrorCode first batch is 4 codes, not the spec's tentatively-listed 6**: spec §3 names
  `KH_CRD_0404`, `KH_SCH_0404`, `KH_CRD_0400`, `KH_CRD_0409`, `KH_KEY_0500`, `KH_SYS_0500` as
  "تقديرياً" (tentative/estimated) — but the task instruction is explicit: "first batch covering
  existing paths only... do not invent speculative codes." Audited every candidate against
  actual current behavior: schema lookups always find-or-create (never fail), the
  atomic-consume 409-shaped conflict already returns as a 200 domain result unchanged (task
  explicitly forbids touching consume behavior), and a bare Bean-Validation-failure code
  wasn't in the spec's list at all despite being clearly necessary — added `KH_SYS_0400` for
  it instead. Net: `KH_CRD_0404`, `KH_KEY_0500`, `KH_SYS_0400`, `KH_SYS_0500`. Documented the
  omissions directly in `ErrorCode`'s Javadoc so a future session doesn't wonder if they were
  forgotten.
- **`unknown_kid` split from `bad_signature`**: not explicitly one of D1–D8's numbered
  decisions, but the spec's own D2 vocabulary example line lists `unknown_kid` separately from
  `bad_signature` — so implementing the split (missing/unresolvable `kid` → `unknown_kid`;
  resolved key, bad signature bytes → `bad_signature`) is following the spec literally, not
  re-litigating it. Required updating one KH-0.5 test
  (`CredentialSigningAndVerificationTest`) whose "key outside the registry" scenario now
  correctly reports `unknown_kid`.
- **`reasonMessage` resolved in `CredentialController`, not `CredentialService`**: keeps the
  domain service i18n-free (`MessageSource`/`LocaleContextHolder` are web-layer concerns);
  the service returns `VerifyResponse` with `reasonMessage=null`, the controller re-wraps with
  the resolved value before returning. A `VerifyResponse` "with null reasonMessage" only ever
  exists transiently inside `CredentialService`, never crosses the module boundary or reaches
  a client.
- **`shared.error` and `shared.web` made `@NamedInterface`s, not folded into `shared`'s open
  root package**: the task instructions pin their locations explicitly (`shared/error/`,
  `shared/web/`), and `shared`'s own package-info already documents that non-root subpackages
  default to module-private under Spring Modulith's convention — so `credential` throwing
  `KhatmException` subtypes or referencing `ErrorEnvelope` in OpenAPI annotations needed
  explicit named-interface exposure + an `allowedDependencies` update, not just relying on the
  existing bare `"shared"` entry (which only ever meant "the root package").
- **`TestBoomController` (test-only, `@Profile("test")`-gated, lives under `src/test/java`)
  for the DoD #1 "synthetic 500" comparison**: needed a deterministic, real HTTP-level trigger
  for `GlobalExceptionHandler`'s catch-all path to assert its envelope shape matches the 404
  case; no existing endpoint can be made to throw an unexpected exception on demand. Never
  reaches the production classpath regardless of the profile guard (test-sourceset only) — the
  guard just keeps it out of `IntegrationTestSupport`-based contexts that don't want it either
  (harmless there anyway, since that suite pins `WebEnvironment.NONE`).
- **`JsonLogEncodingTest` encodes a captured event directly with `LogstashEncoder`, rather than
  asserting against `logback-spring.xml`'s actual profile-switched console output**: spinning
  up a fresh `LoggerContext` from the XML config to test `<springProfile>` branching adds
  real complexity for marginal extra confidence; encoding a real captured `ILoggingEvent` with
  the exact encoder class the XML configures for every non-`local` profile directly proves the
  encoder does what D6 requires, independent of which profile the test JVM happens to run
  under. (The full pipeline was also verified empirically this session anyway — the `test`
  profile's actual `mvn verify` console output was inspected and is real JSON.)
- **`logstash-logback-encoder:8.0`, not the newer `9.0`**: `9.0`'s own POM declares a
  `logback-classic` baseline of `1.5.20`; Spring Boot 3.3.4 manages `1.5.8`. `8.0` declares
  `1.5.6`, safely under what we actually resolve. Picked by checking each candidate version's
  POM directly rather than assuming "newest is fine."

### Session ADR-09-worker (2026-07-16)
- **Custom Redis externalizer, not an official artifact**: verified at the 1.2.4 source tree that
  `spring-modulith-events-redis` does not exist for 1.2.x (the task pre-approved both paths). The
  custom `DelegatingEventExternalizer` mirror what the official amqp/kafka completions do — the
  only Modulith API surface depended on is the public `DelegatingEventExternalizer` + `@Externalized`
  + `RoutingTarget` + `EventExternalizationConfiguration`, all confirmed against the 1.2.4 source
  (not memory). The externalizer is gated by `khatm.events.externalize` (default true) and the
  `test` profile sets it false so the existing Redis-less shared-context suite never attempts an
  `XADD` (and `issue()`'s `CredentialIssued` publication stays a harmless no-op there).
- **`spring-modulith-events-core` promoted to compile scope**: it ships runtime-scoped under
  `spring-modulith-starter-jdbc`, so `DelegatingEventExternalizer` is invisible at compile time
  without this one-line pom change. No version pinned (managed by the BOM); the artifact was
  already transitively present at runtime.
- **Role split via `@ConditionalOnProperty`, not `@Profile`**: `khatm.web.enabled` (default true,
  matchIfMissing) on the two controllers and `khatm.worker.enabled` (default false) on the
  consumer/sweep beans, driven by `api`/`worker` profile documents. Chosen over `@Profile` so (a)
  the existing `test`-profile web tests are unaffected (`matchIfMissing=true` keeps controllers
  on), and (b) the role-guard test can assert the conditional with a property-toggle, not a
  profile swap. The worker image still has no business REST (controllers gated off) — note that
  `/actuator/health` is not exposed (actuator is not a dependency); adding it is future ops work,
  not this task's scope (the frozen stack stays frozen).
- **Synchronous retry + DLQ, not PEL reclaim**: the dispatcher retries a failing handler
  `max-attempts` times in-memory, then `XADD`s to `khatm.dlq` and ACKs the original. This covers
  the task's stated at-least-once + DLQ semantics and is deterministic to test. Cross-instance
  pending reclaim via `XAUTOCLAIM` (crash-recovery of an orphaned consumer's PEL) is a documented
  future hardening, not a gap in the stated contract — called out in `shared/events/README.md`.
- **`CredentialIssued.claimCodeExpiresAt` is nullable**: bare `issue()` creates no claim code
  (`issueClaimCode` is a separate call), so it is `null` at issuance. Kept as a forward-looking
  field for a future consumer rather than fabricating an expiry; documented on the record.
- **Stream test isolation = per-class containers**: the round-trip and DLQ test classes each get
  their OWN Postgres + Redis (not a shared static pair). Two cached worker contexts sharing one
  Redis (both `@Scheduled` pollers alive) was flaky — the sole-consumer-per-broker setup is stable
  and removes cross-context contention. The idempotency test uses a valid-format synthetic stream
  id (`XACK` of a non-existent id is a no-op) rather than a real entry, for determinism.

### Session KH-0.6b (2026-07-17)
- **`consuming_party.api_key_hash` removal forced a real find-or-create redesign, not just a
  column drop**: V2 drops that KH-0.2.1 stand-in column outright (D3 — real API-key auth for a
  consuming party now lives in `rbac.api_key`), but `ConsumingPartyRegistryService#ensure`
  depended on it as its lookup key. Fixed by deriving the row's `id` deterministically from
  `(tenant, code)` via `UUID.nameUUIDFromBytes` (not `Uuidv7` — this one specifically needs to be
  *reproducible* from the same inputs every call, which a time-ordered id can never be) and
  finding-or-creating by that id instead. Internal to `consumer.domain`; no cross-module API
  change.
- **Two `SecurityFilterChain`s, not one — discovered empirically, not from reading docs**: a
  single chain with `SessionCreationPolicy.IF_REQUIRED` still runs Spring Security's default
  `SessionManagementFilter`, whose session-fixation protection treats *any* freshly-set,
  non-anonymous `Authentication` as "just logged in" and eagerly creates an `HttpSession` —
  including the one `ApiKeyAuthFilter` sets fresh on every single request. That directly broke
  spec §3's "API key paths are stateless" and made every API-key call try to persist a session.
  Only `SessionCreationPolicy.STATELESS` disables that filter outright, and `STATELESS` can't be
  scoped to "just these routes" within one chain — hence `apiKeySecurityFilterChain` (matched by
  `Authorization: Bearer khk_...` header presence, `@Order(1)`, stateless, CSRF fully disabled)
  and `sessionSecurityFilterChain` (`@Order(2)`, everything else, `IF_REQUIRED`, CSRF as spec §3
  describes). The same endpoint (e.g. `/issue`) can legitimately be called through either.
- **`KhatmPrincipal` must implement `Serializable`**: Spring Session (Redis, JDK serialization by
  default, D1) persists the whole `SecurityContext` — a non-serializable principal broke *every*
  session-writing request with `NotSerializableException`, not just ones an author would expect
  to touch a session (this is what first exposed the `SessionManagementFilter` issue above: it
  surfaced as `PublicEndpointsNoCredentialsTest`'s anonymous, session-free-looking calls
  mysteriously 500ing).
- **CSRF must be skipped when no session cookie is present, not just on `/verify`+`/login`**:
  `CsrfFilter` runs *before* authentication is resolved. Without this, a fully credential-less
  POST to `/issue` was rejected by CSRF first with a bare `403`, masking spec D9/DoD #3's
  required `401` behind an unrelated failure. Since CSRF exists specifically to stop a forged
  cross-site request riding on an *ambient cookie*, a request carrying no session cookie at all
  has nothing CSRF could be protecting — added a `hasNoSessionCookie` matcher to
  `ignoringRequestMatchers`. Also switched to the plain `CsrfTokenRequestAttributeHandler`
  (not Spring Security's default Xor/BREACH-protected one) so the SPA's "read the `XSRF-TOKEN`
  cookie, send the same value back as `X-XSRF-TOKEN`" pattern needs no encode/decode step — and
  added `CsrfCookieFilter` to force that cookie to actually be *written* at all, since Spring
  Security 6 resolves `CsrfToken` lazily and nothing in a pure JSON API triggers that resolution
  by default.
- **`api_key.env` (`live`/`test`, D2) is derived from the active Spring profile, not a new config
  key**: spec §7's config table is captioned "config surface كامل" (the *complete* surface) and
  does not list one — adding `khatm.auth.api-key.env` would have been exactly the kind of
  undocumented/invented config surface CLAUDE.md's spirit warns against. `ApiKeyService` reads
  `Environment.acceptsProfiles("local","dev","test")` instead.
- **`AdminBootstrap`'s `@Transactional` lives on `run()`, not the `bootstrapIfNeeded()` helper it
  calls**: the classic Spring AOP self-invocation pitfall — `run()` calling
  `this.bootstrapIfNeeded()` internally bypasses the proxy entirely, so a `@Transactional` on the
  callee alone silently ran with *no* transaction, breaking every context boot with
  `TransactionRequiredException` the first time `RoleRepository#assignRole` (a `@Modifying`
  query) ran. `run()` itself is invoked externally by `SpringApplication`'s runner machinery, so
  annotating it directly does go through the proxy correctly. The same pitfall recurred in test
  helper methods (`ScopeGateTest#createUser`) that call `assignRole`; fixed there with an
  explicit `TransactionTemplate` instead, since a test class isn't proxied the way a `@Component`
  is.
- **`AuthService#login` needs `@Transactional(noRollbackFor = AuthenticationException.class)`**:
  every failure path deliberately records an `AUTH_LOGIN_FAILED`/`AUTH_LOCKOUT_TRIGGERED` audit
  row *before* throwing — that `AuthenticationException` is an expected business outcome (D7's
  whole point), not an infrastructure failure, but Spring's default rollback-on-any-
  `RuntimeException` rule was discarding the very audit row the method had just written,
  silently defeating D7's "the real reason lives in the audit log" guarantee. Confirmed by
  `AuthLockoutTest` asserting the audit rows directly.
- **`NoDirectAuditLogInsertTest` (DoD-7's "architectural test") is a pure source-text scan, not
  ArchUnit**: ArchUnit was not one of the four approved new dependencies this session (`pom.xml`
  additions were capped explicitly). A scan for the literal SQL fragment `INSERT INTO audit_log`
  outside `shared/audit/` gives the same "the build catches it, not a manual review" guarantee,
  matching the same pure-file-I/O philosophy `MigrationImmutabilityTest`/
  `ErrorCodesDocGenerationTest` already use in this codebase.
- **`DemoApiKeySeeder` is a new file in `rbac/seed/`, not an addition to
  `credential.seed.DemoSeeder`**: `ApiKeyService` is module-private to `rbac.domain` — Modulith's
  module-ownership boundary means only code inside `rbac` can create an `api_key` row at all, so
  the demo seeder for it has to live there too, regardless of which module's `local`/`dev` seed
  a reader might instinctively look for it in. `AdminBootstrap` (D10) already covers the "demo
  admin user" half of spec §4's `DemoSeeder` note in every profile including `local`, so nothing
  parallel was added for that half.
- **`RbacHttpTestSupport` needed `IntegrationTestSupport`'s manual-`static`-block Testcontainers
  pattern, not `@Testcontainers`/`@Container`**: those JUnit5 annotations bind a container's
  `start()`/`stop()` to the *owning test class's* `beforeAll`/`afterAll` — including for a
  `static` field merely *inherited* from an abstract base. Since every `rbac` HTTP test class
  shares one cached Spring context (identical `@DynamicPropertySource` values), the first
  concrete test class to finish was calling `stop()` on the Postgres/Redis containers out from
  under every subsequent class, which surfaced as HikariCP pool exhaustion (`total=0`) in
  whichever test class happened to run next — not as a container-lifecycle error, which made it
  the least obvious of this session's infra bugs to trace.
- **`TestRestTemplate` needed `JdkClientHttpRequestFactory` (`java.net.http.HttpClient`), not the
  JDK's default `HttpURLConnection`-based client**: the legacy client cannot handle a `401`
  response to a POST it streamed with a known `Content-Length` — it throws `HttpRetryException:
  cannot retry due to server authentication, in streaming mode` internally, surfacing as an
  opaque `ResourceAccessException` with no hint it was ever a `401`. Confirmed empirically that
  disabling `SimpleClientHttpRequestFactory`'s output-streaming mode does *not* avoid this (the
  legacy client's `Authenticator`-retry codepath still triggers); `java.net.http.HttpClient` has
  no such codepath. Every DoD test in `rbac/` deliberately provokes `401`s on POST endpoints, so
  this was not optional for this suite.

### Session KH-0.3-closure (2026-07-17)
- **SHA-pin `trivy-action` only, not every action**: aquasecurity/trivy-action has a concrete,
  documented compromise (2026-03-19 force-pushed tags + malicious v0.69.4) and a command-injection
  CVE in ≤0.33.1, so it gets pinned to the v0.36.0 commit SHA (post-incident) and its Trivy binary
  pinned to `0.72.0`. The other third-party actions (`gitleaks-action@v2`, `docker/*@v3/v5/v6`,
  `appleboy/ssh-action@v1`) stay tag-pinned — that matches this repo's existing `ci.yml` style
  (`actions/*@v4`) and there's no specific threat against them. Applying the stronger control where
  there's a concrete reason, rather than uniformly, keeps CI-correctness risk (a wrong SHA breaks
  the job and can't be locally tested) low while protecting the one real exposure.
- **gitleaks false positive is one test fixture, allowlisted by exact value (both forms)**: the 12
  raw history hits were all the same synthetic `khatm.claims.enc-key` test value
  (`a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=`, decoding to the literal `khatm-test-claims-enc-
  key-32byte`) in two `src/test/java` files — a non-secret fixture, not a leak, so the brief's
  "STOP if real" trigger did not fire. `.gitleaks.toml` allowlists that exact value AND its decoded
  literal (gitleaks base64-decodes and re-scans, so the decoded form trips the rule independently —
  a single allowlist entry was insufficient; re-verified clean). `useDefault = true` keeps the full
  built-in rule set; no rules were weakened. Test files deliberately untouched (a scanner-driven
  rename would be worse than a precise, justified allowlist).
- **CSRF in the smoke flow is handled by a GET, not a token-extraction guess**: after `login`
  (CSRF-exempt), the smoke script does a `GET /api/auth/me` before `/issue`. That GET proves the
  session AND forces `CsrfCookieFilter` to actually write the `XSRF-TOKEN` cookie (Spring Security 6
  resolves the token lazily; a pure-JSON API never triggers it otherwise). The cookie is then read
  from the curl jar and echoed as `X-XSRF-TOKEN` on `/issue`. `/issue` needs `issue` scope, which
  the bootstrap admin's PLATFORM_ADMIN role carries — so the full session-authenticated issue path
  is exercisable, not just the public endpoints.
- **`release.yml` is separate from `ci.yml` and trusts branch protection**: publish/deploy run only
  on push to `main`; there's no cross-workflow `needs: ci.verify` gate because `main` is branch-
  protected (a PR's CI was already green before merge). The deploy half is gated on the
  `STAGING_SSH_HOST` secret existing (`if:` at job level) with a sibling notice job for the skipped
  case — "skipped cleanly, not failed," exactly as the brief required, with no made-up host.
- **`compose-smoke` is one script, not a job full of steps**: `scripts/smoke.sh` owns the whole
  restore-from-zero proof (network create, build, health-wait on JWKS, the e2e cycle, `down -v`,
  re-boot, re-assert) so the same command is the exit criterion locally and in CI. jq was
  deliberately avoided (sed/grep JSON parsing) so Git-Bash and a CI runner are the same environment.
