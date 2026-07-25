> التاريخ الأقدم: docs/STATE-archive-phase0.md
# STATE — khatm-platform
> Updated at the end of EVERY Claude Code session. This file is the session anchor.


## Current phase / task
- Phase 0 — Production Foundation, fully closed (see prior sessions).
- **KH-1.1.5-BE — Dashboard v2 read endpoints** (session `feat/KH-1.1.5-BE-dashboard-stats-v2`,
  2026-07-25, spec `docs/specs/FS-1.5.4-dashboard-stats-v2.md`): added `GET /api/v1/stats/daily`,
  `GET /api/v1/activity`, `GET /api/v1/attention`, `GET /api/v1/admin/signing-keys`, and
  `GET /api/v1/stats/consuming-parties` — unblocks the console's four Dashboard v2 panels. New
  `rbac :: api` surface `ApiKeyOwnerLookup` resolves historical `audit_log.actor_id` to its owning
  consuming party. `mvn verify` green, **274/274 tests (38 new)**. See the spec doc for full design
  detail (module placement, D1–D9).
- **chore/redeem-uses-metadata — holder-facing uses/validity metadata on redeem** (session
  `chore/redeem-uses-metadata`, 2026-07-24, open not merged): micro-session, gap confirmed from
  wallet W1 — `ClaimRedeemResponse` carried no `maxUses`/validity info, so the holder's detail
  screen couldn't show it. Additively extended `ClaimRedeemResponse`/`ClaimRedeemResult` with
  `maxUses` (int) and `expiresAt` (`Instant`), both a redeem-time snapshot sourced from the same
  `Credential` row `ClaimRedemptionService#redeem` already loads (`credential.getMaxUses()` /
  `credential.getValidTo()`) — no new query. **Deliberately did NOT add a live "uses remaining"
  channel**: the holder is anonymous by design (P1), and any polling endpoint keyed by a
  credential ref would be new attack surface — noted explicitly in `ClaimController`'s
  `@Operation` description (not just Javadoc) so the contract itself documents the boundary.
  Contract diff is additive-only (`OpenApiContractTest` green — two new response properties +
  one description-string change, no path/shape removed or altered). `mvn verify` green, 236/236
  tests (existing `ClaimRedemptionServiceTest`/`ClaimControllerHttpTest` cases extended with
  assertions that the response's new fields match the underlying `credential` row, rather than new
  test methods — no new behavior branch to cover, just two more fields on an existing response).
  No new `ErrorCode`, no message-bundle change (no new `messageKey`), so no Arabic-review gate.
  `docs/api/openapi.json` regenerated via `OpenApiContractTest`'s own debug-dump mechanism, not
  hand-edited. **DONE & MERGED via PR #33** (2026-07-24, merge commit `a7ee91a`, fast-forward);
  branch `chore/redeem-uses-metadata` deleted.
- **chore/public-base-url — configurable public base URL** (session `chore/public-base-url`,
  2026-07-23): fixes a confirmed live bug — an issued credential's `status.status_list.uri`
  embedded `http://localhost:8080/...` because `khatm.platform.base-url` always had that default,
  even outside `local`; a wallet on a phone can never resolve it. New `khatm.public-base-url` (env
  `KHATM_PUBLIC_BASE_URL`), bound via a `@ConfigurationProperties` record
  (`shared.PublicUrlProperties`) and resolved by a new `shared.PublicUrlBuilder` bean — the single
  place any module may build an absolute self-referential URL, deliberately never from the
  incoming request's Host header. No default outside `local` — fails startup immediately if
  unset, same no-silent-default pattern as `khatm.keys.soft.passphrase`/`khatm.claims.enc-key`.
  Grepped the whole codebase for request-host-derived URL construction
  (`ServletUriComponentsBuilder`, `getRequestURL`, hardcoded `http://localhost`, `.well-known`/JWKS
  self-URIs, OpenAPI `servers:`) — the confirmed status-list URI was the *only* self-referential
  URL emitted anywhere; `status.domain.StatusListUriBuilder` now delegates its base-URL half to
  `PublicUrlBuilder`, keeping only the `/sl/{tenantSlug}/{listCode}` path shape itself.
  `docker-compose.yml` (both `khatm-api` and `khatm-worker` — the bean is unconditional, so the
  worker role instantiates it too even though nothing in that role calls it yet) now sets
  `KHATM_PUBLIC_BASE_URL` explicitly, documented with a LAN-IP note (README "Running locally" +
  `.env.example`): for testing from a real device (a wallet on a phone), `localhost` only resolves
  on the Docker host, not another device on the same network. `mvn verify` green, **236/236 tests
  (6 new** — `PublicUrlBuilderTest` unit-covers the `build()`/fail-fast logic directly;
  `PublicUrlBuilderFailureTest` mirrors `SoftKeyProviderPassphraseFailureTest`/
  `ClaimsEncryptionKeyFailureTest`'s full-context boot-failure pattern**)**. Every existing
  full-context test that boots the whole app (7 `@SpringBootTest` base classes/standalone tests +
  3 direct `SpringApplicationBuilder` boots) updated to supply `khatm.public-base-url` explicitly,
  since it is no longer defaulted outside `local`. No migration; no message-bundle change (the
  fail-fast throw is a plain `IllegalStateException`, a startup-time infra failure, not a
  `KhatmException` — same precedent as the two secrets it mirrors, so no Arabic-review gate);
  no OpenAPI contract diff (values change, not shapes) — confirmed via `git status`/`git diff` on
  `docs/api/openapi.json`, `docs/error-codes.md`, and both message bundles, all untouched. `shared/
  README.md`, `status/README.md`, `shared/package-info.java` updated. **DONE & MERGED via PR #31**
  (2026-07-23, merge commit `e698014`); branch `chore/public-base-url` deleted.
  - **Post-push CI fix (chore, same PR):** PR #31's own Trivy `fs` gate caught one real,
    session-unrelated dependency CVE — `io.netty:netty-codec` 4.1.135.Final (`CVE-2026-59901`,
    HIGH, `Bzip2Decoder` infinite loop in its RLE state machine, event-loop thread DoS). Same
    minor line had a fix, so a patch-level `pom.xml` override cleared it (`netty.version` →
    `4.1.136.Final`); `mvn verify` re-confirmed green (236/236) before pushing the fix. The
    re-run also hit the same transient Maven Central 429 rate-limit flake documented at
    KH-1.1.3-BE (Trivy's own dependency-graph resolution for `netty-parent`'s POM) — not a
    finding, cleared by re-running the job, no code change.
  - Also committed on this branch (first commit, pre-existing uncommitted work from before the
    session started): the `docs/STATE.md` → `docs/STATE-archive-phase0.md` history split.
- **KH-1.1.3-BE — bulk issuance + stats endpoint (+ OpenAPI security schemes)** (session
  `feat/KH-1.1.3-BE-bulk-and-stats`, 2026-07-22): support-mode session, brief itself was the spec
  (same precedent as KH-1.1-BE/KH-1.6-early/KH-1.2.2/KH-1.4.3/KH-1.4.4-BE). **This was the last
  planned platform session before V1 closure** — unblocks console C3's bulk-issue CSV wizard and
  C4's pilot-metrics dashboard (KH-1.5.3 commitment). `mvn verify` green, **230/230 tests (22 new,
  up from 208)**; the full live-compose e2e (DoD #2) ran for real against the existing
  `docker compose` stack: bulk-issued 3 items of the demo schema with `mintClaimCodes:true` →
  redeemed one code → verified it (valid) → consumed it with the demo consuming-party key → `GET
  /api/v1/stats` reflected all of it (`issued`+3, `claimsRedeemed`+1, `verifyOk`+1, `consumed`+1).
  **DONE & MERGED via PR #29** (2026-07-22, merge commit `c138da7`); branch
  `feat/KH-1.1.3-BE-bulk-and-stats` deleted. Arabic-review gate for `credential.bulk-validation-failed`
  confirmed by Majd before merge, no wording changes. Confirmed `main` included PR #27/#28
  (KH-1.4.4-BE, merges `d4e0c47`/`6d8c4ab`) at session start via `git log` directly, per protocol.
  **PR #29's own CI caught two real, session-unrelated dependency CVEs** (Trivy's `fs` gate,
  first surfaced by this PR simply because it was the first to run CI since they were published):
  `org.postgresql:postgresql` 42.7.11 (`CVE-2026-54291`, HIGH, SCRAM-SHA-256-PLUS downgrade MITM
  bypass) and `jackson-core` 2.17.3 (`GHSA-r7wm-3cxj-wff9`, HIGH, async-parser
  `maxNumberLength` bypass) — both cleared with patch-level `pom.xml` property overrides
  (`postgresql.version` → `42.7.12`, new `jackson-bom.version` → `2.18.8`), same pattern
  KH-0.3-closure established; `mvn verify` re-confirmed green (230/230) after the bump, no
  behavior change. One CI re-run was also needed for an unrelated, transient Maven Central 429
  rate-limit Trivy's own dependency-graph resolution hit mid-scan — not a finding, cleared on
  retry with no code change. See "Last completed" → Session KH-1.1.3-BE for the full breakdown.
- **KH-1.4.4-BE — consuming-party admin plane + `ensure()` race closure** (session
  `feat/KH-1.4.4-BE-consuming-party-admin`, 2026-07-21): support-mode session, brief itself was the
  spec (same precedent as KH-1.1-BE/KH-1.6-early/KH-1.2.2/KH-1.4.3). Gives the console's
  consuming-parties screen + consume simulator (session C2b, other repo) the HTTP surface KH-1.4.3
  left missing: parties were only ever created by `DemoApiKeySeeder` or implicitly via
  `ConsumingPartyRegistryService#ensure`, and allowlisting was seeder/test-only. `mvn verify` green,
  **208/208 tests (27 new, up from 181)**; the full live-compose e2e (DoD #2) ran for real (create →
  allow → mint → consume → suspend → 401 → activate → consume). **DONE & MERGED via PR #27**
  (2026-07-22, merge commit `d4e0c47`); branch `feat/KH-1.4.4-BE-consuming-party-admin` deleted;
  Arabic-review gate for the four new `consumer.*` keys confirmed by Majd before merge, no wording
  changes. Confirmed `main` included PR #25 (KH-1.1-BE, merge `7e5cbc1`) at session start via `git
  log` directly, per protocol. See "Last completed" → Session KH-1.4.4-BE for the full breakdown.
- **KH-1.1-BE — schema management + credential search + idempotency race closure** (session
  `feat/KH-1.1-BE-schema-mgmt-and-search`, 2026-07-21): three-part support-mode session, brief
  itself was the spec (no separate spec doc, same precedent as KH-1.6-early/KH-1.2.2/KH-1.4.3).
  `mvn verify` green, 181/181 tests (35 new, up from 146). DONE & MERGED via PR #25 (2026-07-21,
  merge commit `7e5cbc1`, fast-forward — `main` had not diverged); branch
  `feat/KH-1.1-BE-schema-mgmt-and-search` deleted. Confirmed `main` included PR #24 (KH-1.4.3) at
  session start via `git log` directly, per protocol. See "Last completed" → Session KH-1.1-BE for
  the full three-part breakdown.
- **KH-1.4.4-BE is DONE & MERGED via PR #27** (2026-07-22, merge commit `d4e0c47`); branch
  `feat/KH-1.4.4-BE-consuming-party-admin` deleted. Arabic-review gate for the four new `consumer.*`
  keys **confirmed by Majd** before merge, no wording changes. Recorded via chore branch
  `chore/state-update-post-pr27` (same pattern as PR #26). See the entry immediately above and
  "Last completed" → Session KH-1.4.4-BE.
- **Older tasks were moved into /docs/STATE-archive-phase0.md


## Last completed
- 2026-07-22: KH-1.1.3-BE — bulk issuance + stats endpoint (+ OpenAPI security schemes).
  Support-mode session, brief itself was the spec. `mvn verify` green, 230/230 tests (22 new, up
  from 208). **DONE & MERGED via PR #29** (2026-07-22, merge commit `c138da7`); branch
  `feat/KH-1.1.3-BE-bulk-and-stats` deleted. Confirmed `main` included PR #27/#28 (KH-1.4.4-BE) at
  session start via `git log` directly, per protocol.
  - **Post-push CI fix (chore, same PR):** PR #29's own Trivy `fs` gate caught two real
    dependency CVEs unrelated to this session's code — `org.postgresql:postgresql` 42.7.11
    (`CVE-2026-54291`, HIGH) and `jackson-core` 2.17.3 (`GHSA-r7wm-3cxj-wff9`, HIGH). Both had a
    fixed version in the same minor line, so patch-level `pom.xml` overrides cleared them
    (`postgresql.version` → `42.7.12`; new `jackson-bom.version` property → `2.18.8`, Spring
    Boot's own recognized override point for the whole Jackson BOM import, keeping every
    `jackson-*` artifact on one matching release rather than bumping `jackson-core` alone) — the
    exact same patch-level-bump-over-allowlist-entry preference `.trivyignore`'s own header
    states and KH-0.3-closure already established. `mvn verify` re-confirmed green (230/230,
    no behavior change) before pushing the fix. A second CI run also hit a transient Maven
    Central 429 (rate-limited mid-scan while Trivy resolved `netty-parent`'s POM for its own
    dependency-graph analysis) — an infrastructure flake, not a finding; cleared by re-running the
    job on a fresh runner, no code change.
  - **D1/D2 — bulk issuance, `POST /api/v1/credentials/bulk`:** new `credential.domain
    .BulkIssuanceService` (module-private, new bean — deliberately *not* a method on
    `CredentialService` itself, so each item's call to `CredentialService#issue`/`#mintClaimCode`
    goes through Spring's real transactional proxy rather than a self-invocation, the same
    `AtomicConsumptionRecorder` rationale). Up to 200 items, one schema per batch; each item issues
    independently in its own transaction — one bad row never rolls back the batch. Response:
    `{total, succeeded, failed, results:[{index, status, id?, ref?, claimCode?, error?}]}`,
    index-aligned. New `KH-CRD-0400` (`credential.bulk-validation-failed`, `{0}`-substituted
    reason) for a batch-level empty/oversized rejection — thrown before any item is processed,
    never counted as a per-item failure. A draft/archived-schema item fails per-item with the
    *existing* `KH-SCH-1409` guard (`SchemaCatalog#ensurePublished`), reused unchanged — no new
    schema-status logic.
  - **D3 — claim codes:** `mintClaimCodes: true` mints a code per successfully issued item via the
    unchanged `CredentialService#mintClaimCode` path, returned once in that item's result. If the
    mint call itself fails after a successful issue, the row is reported `FAILED` even though the
    underlying credential was already committed (an accepted edge case, documented on
    `BulkIssuanceService`'s Javadoc — not exercised by the batch's own transaction boundary).
  - **D5/D6 — stats endpoint, `GET /api/v1/stats`:** new `shared.web.StatsController` (stays
    inside the `shared` module — it only depends on `shared.audit`, a same-module named interface,
    so no new Modulith dependency edge). A plain `GROUP BY action` aggregation
    (`AuditService#countActionsInWindow`, new) over `audit_log`, session-gated
    (`ScopeGuard#requireUserSession`, same stance as credential search) — `?from=&to=` optional
    ISO-8601 instants, default last 30 days, `[from, to)` semantics. **D6 verify-against-the-code
    finding:** `CREDENTIAL_VERIFY_OK`/`CREDENTIAL_VERIFY_FAILED` did not exist — added both new
    `AuditAction`s, recorded by `CredentialController#verify` *after* `CredentialService#verify`
    returns, deliberately outside that method's own `readOnly = true` transaction (a read-only
    transaction cannot accept the write; `ref` is read from the already-decoded `claims` map's
    `"ref"` entry, never re-parsed). Every one of D5's seven counters now has a real data source —
    no counter had to fall back to a hardcoded `0`.
  - **`V6__audit_log_stats_index.sql`** (the one additive migration, verified necessary — no prior
    index existed on `audit_log` besides its identity PK): `(tenant_id, occurred_at)`, backing the
    stats aggregation's range scan. `MigrationImmutabilityTest` green; checksum appended to
    `db/migration-checksums.lock`.
  - **D7 — OpenAPI security schemes:** `shared.config.OpenApiConfig` gained
    `components.securitySchemes`: `sessionCookie` (apiKey-in-cookie, `KHATM_SESSION`) and
    `apiKeyBearer` (http bearer, format `khk_...`) — closes the C2b-flagged docs gap (the published
    contract declared no security schemes at all). **Scope decision (brief's own escape hatch
    invoked):** scheme declarations + descriptions only, no per-operation `@SecurityRequirement`
    wiring — auditing every endpoint's exact auth story individually was judged more than this
    additive docs-gap fix needed for one session. Purely additive; no path or existing schema
    changed.
  - **`docs/api/openapi.json` + `docs/error-codes.md`** regenerated via their own tests
    (`OpenApiContractTest`, `ErrorCodesDocGenerationTest`), not hand-edited — additive-only (new
    `/bulk` and `/stats` paths + DTOs + security schemes, one new `KH-CRD-0400` row).
    `credential/README.md`, `credential/package-info.java`, `shared/README.md`,
    `shared/package-info.java` updated. `rbac.security.SecurityConfig`'s Javadoc gained the two new
    per-endpoint decisions (`/bulk` reuses `/issue`'s gate verbatim; `/stats` reuses credential
    search's gate verbatim).
  - **Tests (22 new):** `credential.domain.BulkIssuanceServiceTest` (7 — happy path + per-item
    audit rows, `mintClaimCodes` one-time code + its own audit row, mixed-batch per-item failure
    with index alignment and the batch audit row still recorded, draft-schema-item and
    archived-schema-item both failing with the reused `KH-SCH-1409`, empty-batch and
    too-many-items both `ValidationException`), `rbac.BulkIssueScopeGateTest` (5 — 401/403
    CONSUMING_PARTY key/403 TENANT key missing scope/200 TENANT key with scope/200
    ISSUER_OPERATOR session), `shared.audit.AuditStatsTest` (3 — group-by-action counting via
    direct JDBC-seeded rows with controlled `occurred_at`, window exclusion, `[from, to)`
    exclusive-upper-bound — every assertion is a delta, not a bare count, since this shared-context
    suite's `audit_log` accumulates rows from every other test class), `rbac.StatsScopeGateTest`
    (5 — 401/403 full-scope key/200 session with counters envelope/200 explicit window
    echoed/400 malformed window param), `rbac.CredentialVerifyAuditTest` (2 — valid presentation
    records `CREDENTIAL_VERIFY_OK` with the resolved ref and no claim content in `detail`;
    malformed presentation records `CREDENTIAL_VERIFY_FAILED` with no resolved ref).
  - **Arabic-speaker review gate (spec FS-0.6a §4)** for `credential.bulk-validation-failed`:
    **confirmed by Majd (2026-07-22) before PR #29's merge**, no wording changes needed — same
    pattern as every prior session's new-key set.
- 2026-07-21: KH-1.4.4-BE — consuming-party admin plane + `ensure()` find-or-create race closure.
  Support-mode session, brief itself was the spec. `mvn verify` green, 208/208 tests (27 new, up
  from 181). DONE & MERGED via PR #27 (2026-07-22, merge commit `d4e0c47`); branch
  `feat/KH-1.4.4-BE-consuming-party-admin` deleted. Confirmed `main` included PR #25 at session
  start via `git log` directly, per protocol.
  - **Admin plane (D1/D3), `admin` scope, under `/api/v1/admin/consuming-parties`:** new
    `consumer.api.ConsumingPartyAdmin` (impl `consumer.domain.ConsumingPartyAdminService`,
    module-private) + `consumer.web.ConsumingPartyAdminController`: `GET` (list, newest-first, each
    with resolved `allowedSchemas` as `[{schemaId, schemaCode}]`), `POST` (register), `POST
    /{id}/suspend` + `/activate`, `POST /{id}/allowed-schemas` (returns the updated view) + `DELETE
    /{id}/allowed-schemas/{schemaId}`. The gate is the *existing* `/api/v1/admin/**` →
    `ScopeGuard.requireScope("admin")` rule — no new scope, no seeded-role migration (same MVP stance
    as KH-1.1.1 schema management; granular `consumer:manage` waits for KH-2.2).
  - **Key mint lives in `rbac.web`, not `consumer` (D3, hard constraint 2):** `POST
    /{id}/api-keys` is `rbac.web.ConsumingPartyKeyController` (mints a `CONSUMING_PARTY` key, scope
    `consume`, plaintext once). It could not live in `consumer.web` — only `rbac` may create
    `api_key` rows (`ApiKeyService` is module-private to `rbac.domain`), and `consumer → rbac` would
    cycle against the existing `rbac → consumer::api` seeder dependency (`ModulithBoundariesTest`
    stayed green + acyclic). It calls `ConsumingPartyAdmin#get` to 404 (`KH-CNS-0404`) an unknown
    party before minting; revocation reuses the existing `/api/v1/admin/api-keys/{id}/revoke`.
  - **D2 — identity stays deterministic, duplicate = 409 (implementer's pick):** `create(code,
    nameI18n)` derives the row id `UUID.nameUUIDFromBytes(tenant:code)` — identical to `ensure` — so
    explicit creation and implicit ensure can never diverge into two rows; a second create of the
    same code is `KH-CNS-0409`, never a silent overwrite or a second row (proven by a one-row DB
    assertion). `code` validated `^[a-z0-9][a-z0-9-_]{1,62}$` → `KH-CNS-0400` on a bad format.
  - **Migration `V5__consuming_party_code.sql` (D2, hard constraint 3):** `consuming_party` had NO
    `code` column (verified against the entity first, per the KH-1.4.3 Part B lesson — the id
    derivation always hashed `tenant:code` but never persisted `code`). `V5` adds `code text` +
    `UNIQUE (tenant_id, code)`, backfilling any pre-existing rows with `'legacy-' || id` (their
    original code is unrecoverable from the one-way hash; a real deployment has none yet). Entity now
    implements `Persistable<UUID>` so a fresh row forces a true `INSERT` (deterministic conflict on a
    lost race, never a silent merge/UPDATE-clobber of the winner). V1–V4 untouched;
    `MigrationImmutabilityTest`/`MigrationCleanBootTest` green; checksum appended to
    `db/migration-checksums.lock`.
  - **D4 — SUSPENDED bites, in the auth path:** new `ConsumingPartyRegistry#isActive`, consulted by
    `rbac.domain.ApiKeyService#verify` — a `CONSUMING_PARTY` key whose party is `SUSPENDED` returns
    empty exactly like a revoked key, funnelling down the same `API_KEY_AUTH_FAILED` / 401
    `KH-RBC-1401` path (matched to the revoked-key path, as the brief asked). A `null`-owner guard
    tolerates legacy/test keys with no party. Proven both by `ConsumeApiKeyGateTest`
    (`consume_withSuspendedParty_returns401_andWorksAgainAfterActivate`) and the live e2e.
  - **D5 — allowlist referential sanity:** `allowSchema` requires the party (`KH-CNS-0404`) and the
    schema (`KH-CNS-1404`, a second CNS 404 — via `schema :: api`'s `SchemaCatalog#findById`, any
    non-deleted status accepted); `disallowSchema` is a pure idempotent DELETE → **204 no-op** even
    for an unknown party (implementer's pick), auditing only when a row was actually removed.
  - **D6 — `ensure()` race CLOSED (KH-1.1-BE Part C "Next up" #4):** `ensure` is now deliberately
    NOT `@Transactional`, so each repo call runs in its own transaction and a lost race's
    `saveAndFlush` `DataIntegrityViolationException` rolls back cleanly — the catch re-reads the
    winner's row on a clean connection (no aborted-transaction poisoning, unlike
    `AtomicConsumptionRecorder`, exactly as the brief's D6 sketch predicted). Its one runtime caller,
    `CredentialService#consume`, was already non-transactional for the same family of reason.
    Regression test `db.ConsumingPartyEnsureRaceTest`: two real threads race a brand-new code → same
    id, exactly one row.
  - **Errors & audit (D7):** new `KH-CNS-0400`/`0404`/`1404`/`0409` (both bundles, same commit) and
    new `AuditAction.CONSUMING_PARTY_{CREATED,SUSPENDED,ACTIVATED,SCHEMA_ALLOWED,SCHEMA_DISALLOWED}`
    (entityRef = party `code`, detail carries `schemaId`; key mint reuses `API_KEY_CREATED`). All
    writes via `AuditService#record` (`NoDirectAuditLogInsertTest` still green).
  - **Both new controllers gated `@ConditionalOnProperty(khatm.web.enabled, matchIfMissing=true)`**
    — the business-controller pattern (Credential/Claim/Status/Jwks), keeping the worker role clean.
  - **`docs/api/openapi.json` + `docs/error-codes.md`** regenerated via their own tests
    (`OpenApiContractTest` → `target/openapi-generated.json`, `ErrorCodesDocGenerationTest`), not
    hand-edited — additive-only (6 new consuming-parties paths + DTOs, 4 new `KH-CNS-*` rows; contract
    diff 478 insertions / 0 deletions). `consumer/README.md`, `consumer/package-info.java` updated.
  - **Tests (27 new):** `consumer.domain.ConsumingPartyAdminServiceTest` (12 — create/idempotency-
    one-row/invalid-code/get-404/suspend+activate+isActive/idempotent-suspend/allow+audit/allow-404s/
    disallow-idempotent-no-op/list-newest-first), `rbac.ConsumingPartyAdminGateTest` (12 — 401/403
    CP-key/403 tenant-no-admin/200 admin-key gate, full HTTP lifecycle walk with audit rows, 409
    duplicate + one-row, 400 invalid code, 404 party/schema, 204 disallow no-op, mint returns key /
    mint-404), `db.ConsumingPartyEnsureRaceTest` (1 — D6), `ConsumeApiKeyGateTest` (+1 — D4
    suspend→401→activate), `AuthSecretsNotLoggedTest` (+1 — mint rawKey never logged).
  - **Arabic-speaker review gate (spec FS-0.6a §4)** for the four new `consumer.*` keys
    (`consumer.invalid-code`, `consumer.party-not-found`, `consumer.allowlist-schema-not-found`,
    `consumer.duplicate-code`): **confirmed by Majd (2026-07-22) before PR #27's merge**, no wording
    changes needed — same pattern as every prior session's new-key set. `MessageBundleParityTest`
    green throughout.
- **Older last completed works were moved into /docs/STATE-archive-phase0.md


## Decisions made

### All made decisions before 2026-07-23 moved into /docs/STATE-archive-phase0.md

> Durable conventions formerly logged here

> Durable conventions formerly logged here (entity visibility, the Checkstyle
> logger/MethodName exceptions) now live in `docs/CONVENTIONS.md` §2/§5 — this file only
> keeps session-scoped decisions. The stale "`ddl-auto: update` kept" note has been removed
> (superseded by KH-0.2.1: `ddl-auto: validate` is live — see Last completed above).

## Environment facts
- Local: Windows + IntelliJ + Docker Desktop. Shared network `khatm-net` created.
- DB exposed on :5432 for IntelliJ; API on :8080.
- Maven 3.9.9 (must export PATH manually: `export PATH="$MAVEN_HOME/bin:$PATH"`).
- Toolchain is Java 21 (`pom.xml` `java.version`/`maven.compiler.release`). Both JDK 17
  (`C:\Program Files\Java\jdk-17`, original) and JDK 21 (Eclipse Temurin,
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`) are installed on this machine;
  `JAVA_HOME` must point at the JDK 21 install for builds to target the right release —
  IntelliJ project SDK and JAVA_HOME both point at Eclipse Temurin 21 (fixed manually
  2026-07-15) — the JDK 17 install remains on disk but is unused.
- Default tenant strategy: single default tenant row until KH-2.1 — fixed UUID
  `00000000-0000-0000-0000-000000000001`, seeded by `V1__baseline.sql`, mirrored in Java as
  `sy.khatm.platform.shared.TenantContext.DEFAULT_TENANT_ID`.
- Docker Desktop on this machine needs `src/test/resources/docker-java.properties`
  (`api.version=1.44`) for Testcontainers to connect at all (see decisions above).
- Docker Desktop does not auto-start on login on this machine — `docker info` fails until it's
  launched manually (or via `"/c/Program Files/Docker/Docker/Docker Desktop.exe" &`, then
  polled until `docker info` succeeds, ~10–30s). Needed before any Testcontainers-backed
  `mvn verify` run.
- **KH-0.6b: `KHATM_BOOTSTRAP_ADMIN_USERNAME`/`KHATM_BOOTSTRAP_ADMIN_PASSWORD` are now required
  outside `local`** — same no-silent-default pattern as `KHATM_KEYS_PASSPHRASE`/
  `KHATM_CLAIMS_ENC_KEY`. `AdminBootstrap` fails startup immediately if either is blank and no
  `app_user` row exists yet for the default tenant. The `local` profile document in
  `application.yml` supplies a documented default (`admin` / a printed placeholder password) so
  `docker compose up` still needs zero setup.

### Session chore/swagger-and-flagged-fixes (2026-07-18)
- **springdoc-openapi pinned to 2.6.0, not the 2.8.x line the old `-api`-only artifact used**:
  confirmed empirically that 2.7.0+ needs Spring Framework 6.2 (`LiteWebJarsResourceResolver`,
  `NoClassDefFoundError` on this pom's Boot 3.3.13 / Framework 6.1 line) for its UI
  auto-configuration. 2.6.0 is the last release still targeting Boot 3.0.x–3.3.x. Re-check this
  pin whenever the frozen Boot 3.x line itself moves to 3.4+.
- **Swagger UI's version string comes from Maven resource filtering (`@project.version@` in
  `application.yml`), not a `spring-boot-maven-plugin` `build-info` execution**: the session's
  hard constraint limited `pom.xml` changes to the springdoc swap and the spring-security
  override, so adding a new plugin execution was out of scope. `spring-boot-starter-parent`
  already filters `application.yml` with the `@...@` delimiter for every child project — zero pom
  changes needed to resolve a real version string in `shared.config.OpenApiConfig`.
- **CVE-2026-22732: whole `spring-security.version` line bumped, not `spring-security-web`
  alone** — the brief's preferred path was tried first and genuinely does not work on this
  codebase: `spring-security-web` 6.5.9 references a `spring-security-core` 6.4+-only class
  (`SecurityAnnotationScanners`), so leaving `spring-security-core` at the Boot BOM's 6.3.10
  breaks every Spring MVC context at `requestMappingHandlerAdapter` creation. This was confirmed
  by actually running `mvn verify`, not inferred from a compatibility matrix — the single-artifact
  override resolved cleanly at the dependency-tree level and only failed at runtime. Fell back to
  the brief's own documented contingency (override `spring-security.version` itself, 6.5.9,
  moving config/core/crypto/test/web together) — this is the "record which path was taken and
  why" the brief asked for.
- **`KeyBootstrapRoleGuardTest` uses a mocked `KeyLifecycleService`, not a real one, and never
  exercises `KeyBootstrap#run`**: `ApplicationContextRunner` (unlike a full `SpringApplication`
  boot) never invokes `ApplicationRunner`/`CommandLineRunner` beans — that's
  `SpringApplication.callRunners()`'s job, which this lightweight harness deliberately skips. The
  test only proves the `@ConditionalOnProperty` wiring (bean present/absent), the same scope
  `shared.events.WorkerRoleGuardTest` already established as sufficient for this class of
  role-gate test; `shared.events.WorkerProfileSecurityBootTest`'s real full-context boot is what
  actually proves the worker role never runs it in practice.
- **`scripts/smoke.sh`'s `wait_for_api` call has to stay, even after the sequenced-boot workaround
  it was paired with is gone**: reverting `boot_stack` to a single `docker compose up -d --build`
  very nearly dropped the `wait_for_api` call along with it (both lived in the old, more complex
  `boot_stack` function) — `docker compose up -d` returns as soon as containers start, not once
  Tomcat is actually accepting connections, so the very first local re-run failed `check_jwks`
  with "Empty reply from server" before catching this. `wait_for_api` is now called explicitly
  after `boot_stack` in both phases of the script, independent of how `boot_stack` itself boots
  the stack.

## Open decisions / blockers
- **`claim_code.disclosures_enc` — CLOSED FOR GOOD (KH-1.2.1, 2026-07-18).** All three thirds now
  real: encryption (KH-0.4, `CredentialService#issueClaimCode`, AES-256-GCM, key from
  `khatm.claims.enc-key`), expiry-zeroing (ADR-09-worker, `ClaimCodeExpiryWorker#sweep`), on-claim
  zeroing (KH-1.2.1, `ClaimRedemptionService#redeem` — `POST /api/v1/claims/redeem`, spec FS-1.2.1
  D2, `SELECT ... FOR UPDATE`-locked single transaction, race-safe against the sweep). Every
  `disclosures_enc` row ends up `NULL` exactly once, either the moment a wallet claims it or the
  moment it expires unclaimed, never later, never both, never neither. Nothing left open under
  this blocker.

## Next up (ordered)

**Platform v1 is complete** (auth, claim delivery + minting, signed status list, consumption
hardening, versioned published contract — see "Current phase / task" above), and support mode is
now underway (KH-1.1-BE closed schema management + credential search + the consume idempotency race;
KH-1.4.4-BE added the consuming-party admin plane + closed the `ensure()` race; KH-1.1.3-BE added
bulk issuance + the stats endpoint + OpenAPI security schemes; KH-1.1.5-BE, this session — **not yet
merged, no PR opened** — added Dashboard v2's five read endpoints, unblocking the console's four
placeholder panels — see "Current phase / task" above for the full breakdown).

1. **KH-1.1.5-BE needs a PR + merge** (this session's own work) — branch
   `feat/KH-1.1.5-BE-dashboard-stats-v2`, `mvn verify` green (274/274). Not opened this session
   since it wasn't asked for.
2. **Console's four Dashboard v2 panels (other repo)** — once merged, wiring the console side to
   real data is the already-scoped follow-up this session's brief named (khatm-console's
   `docs/STATE.md`, "Next up" #5).
3. **"Signing key approaching rotation" attention item — deliberately not built this session**
   (KH-1.1.5-BE spec D5): needs a new, narrow, state-only `key :: api` surface Majd declined to add
   for now, to keep `key`'s "other modules must never see rotation" stance untouched. Revisit only
   if that boundary decision changes — see `docs/specs/FS-1.5.4-dashboard-stats-v2.md` D5.
4. **C2 / C2b / C3 / C4 (console, other repo)** — the console team's active milestone; the bulk-issue
   + stats endpoints (plus KH-1.4.4-BE's consuming-parties admin plane and KH-1.1-BE's schema
   management/credential search) exist specifically to unblock the console's remaining screens
   (issue wizard, pilot-metrics dashboard, consuming-parties screen, consume simulator). No further
   platform-side work is scheduled ahead of a concrete console ask.
5. ~~KH-1.1.3-BE — bulk issuance endpoint + a stats/counters endpoint~~ — **CLOSED:**
   `POST /api/v1/credentials/bulk` + `GET /api/v1/stats`, both scope-gated, both
   backed by the reused single-issue path / `audit_log` aggregation respectively — no new
   bookkeeping. See "Last completed" → Session KH-1.1.3-BE for the full breakdown.
6. KH-0.3.3 activation — **config, not code**: set the staging secrets in `docs/deploy-staging.md`
   and the `release.yml` deploy job runs on the next push to `main`. (The publish half is already
   live; only the gated deploy half waits on a host — Majd.)
7. ~~`ConsumingPartyRegistryService#ensure` find-or-create race~~ — **CLOSED (KH-1.4.4-BE):**
   `ensure` is no longer `@Transactional` and the entity forces a true `INSERT`
   (`Persistable`), so a lost race's `DataIntegrityViolationException` rolls back cleanly and the
   catch re-reads the winner's row directly — exactly the shape flagged here. Regression test
   `db.ConsumingPartyEnsureRaceTest`.
8. KH-2.2 — full RBAC (replaces D5's lean `role.scopes text[]` with real Permission tables, admin
   console for user/role management, granular `schema:manage`/`consumer:manage` scopes replacing the
   MVP `admin`-scope stand-in) + RBAC-gated REST endpoint for `KeyLifecycleService.rotate()`.
9. KH-2.3 — KMS-backed `KeyProvider` (D3 swap), KH-3.1 — HSM.

## Standing conventions (promoted to docs/CONVENTIONS.md §7)
- **Work rules 2 & 3 (error handling & i18n)** → `docs/CONVENTIONS.md §7.1`.
- **Spring Security per-endpoint discipline (KH-0.6b)** → `docs/CONVENTIONS.md §7.2`.
