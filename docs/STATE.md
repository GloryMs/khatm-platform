> التاريخ الأقدم: docs/STATE-archive-phase0.md
# STATE — khatm-platform
> Updated at the end of EVERY Claude Code session. This file is the session anchor.


## Current phase / task
- Phase 0 — Production Foundation, fully closed (see prior sessions).
- **KH-2.2a-BE — RBAC scope registry (D1–D4)** (session `feat/KH-2.2a-BE-scope-registry`,
  2026-07-28, spec `docs/specs/FS-2.2-rbac-granularity.md`): replaces the KH-0.6b coarse `admin`
  scope stand-in with a nine-scope deny-by-default registry (`issue, verify, consume, revoke,
  schema:manage, consumer:manage, key:manage, tenant:admin, platform:admin`) and re-gates every
  `/api/v1/admin/**` endpoint per its own family. `mvn verify` green, **344/344 tests (17 new)**.
  **PR #43 opened, NOT merged** — pending Majd's review (breaking scope-semantics change by
  design, spec V3 — flagged prominently in the PR body). No new `ErrorCode`/message key (every
  403 reuses `KH-RBC-0403`/`error.rbc.forbidden`), so no Arabic-review gate.
  - **Verify-against-code findings (recorded before writing, per the brief):** built the full live
    endpoint→gate inventory directly from `SecurityConfig`/`ScopeGuard`/every `@RestController`
    (not assumed from the spec's D2 mapping shape) — the entire `/api/v1/admin/**` surface was one
    `ScopeGuard.requireScope("admin")` wildcard covering four independent controllers.
    Session-scoped scopes are baked into the `HttpSession` at login time
    (`rbac.domain.AuthService#login`) and not otherwise cached — confirmed the only staleness
    window is "existing sessions need re-login post-deploy" (already the documented, accepted
    trade-off), while API-key scopes are read fresh from `api_key.scopes` on every request
    (`ApiKeyService#verify`), no caching concern there at all.
  - **D1 — `rbac.security.ScopeRegistry`:** the nine-scope catalog. Deny-by-default pinned by two
    new source-scan tests (same technique as `SystemAccessCallerAllowlistTest`):
    `LegacyAdminScopeAbsenceTest` (no source file anywhere passes the literal string `"admin"` as
    a scope value) and `AdminPathScopeCoverageTest` (every live `/api/v1/admin/**` mapping falls
    under one of `SecurityConfig`'s four declared path families, never a silent fall-through to
    `anyRequest().authenticated()`).
  - **D2 — full re-gate, verified endpoint-by-endpoint:** schema reads (`GET
    /api/v1/schemas[/{id}]`) tightened from bare `authenticated()` to any of `issue/verify/consume
    /revoke/schema:manage` (spec V2 — an `ISSUER_OPERATOR` needs schema read without
    `schema:manage`); schema writes → `schema:manage`; `/api/v1/admin/tenants/**` →
    `platform:admin` exclusively (the one cross-tenant plane); `/api/v1/admin/consuming-parties/**`
    (+ its key-mint sub-path, `rbac.web.ConsumingPartyKeyController`) → `consumer:manage`;
    `/api/v1/admin/api-keys/**` → `tenant:admin` (self-service) **or** `platform:admin` (explicit
    foreign `tenantId`, see D4); `/api/v1/admin/signing-keys` → `key:manage`. Full
    endpoint→scope table in the PR body. Action-scoped endpoints (`issue`/`consume`/`revoke`) and
    the session-only family (credential search/stats/activity/attention) are unchanged, out of
    this rescoping's scope.
  - **D3 — `V10__scope_registry_rescope.sql`** (append-only, data-only): `PLATFORM_ADMIN` = all
    nine scopes; `TENANT_ADMIN` = all except `platform:admin`; `ISSUER_OPERATOR` = `issue, verify,
    revoke`. `admin` scrubbed from every role, clean cut (spec V3, no coexistence period).
    V1–V9 untouched, `MigrationImmutabilityTest`/`MigrationCleanBootTest` green, checksum
    appended. New `db.SeededRoleScopesTest` pins the exact post-migration scope sets per role and
    asserts zero roles anywhere still carry `admin`.
  - **D4 — a real cross-tenant gap found while re-gating, closed:** `POST
    /api/v1/admin/api-keys`'s explicit-`tenantId` branch (a platform admin provisioning a newly
    onboarded tenant's first key) let `ApiKeyService.create(..., tenantId)` switch
    `TenantContext` with **no check that the caller actually held `platform:admin`** — masked
    pre-rescoping because `PLATFORM_ADMIN` and `TENANT_ADMIN` shared the same coarse `admin`
    scope; re-gating this endpoint to bare `tenant:admin` would have *widened* the exposure (any
    tenant admin naming an arbitrary foreign tenant) had it shipped unfixed. New
    `shared.OnBehalfOfExecutor` (mirrors `SystemAccessExecutor`'s shape, spec D4): re-verifies
    `platform:admin` directly against the live `SecurityContextHolder` authorities (duplicates the
    `SCOPE_<scope>` convention rather than importing module-private `rbac.security` —
    `shared.audit.AuditService` already reads `SecurityContextHolder` directly for the identical
    reason), records `AuditAction.ON_BEHALF_OF` (entityRef = target tenant slug, written under the
    caller's own ambient tenant *before* the switch, per spec D4's own wording), then switches
    `TenantContext` to the explicit target. `shared.OnBehalfOfCallerAllowlistTest` pins its one
    enumerated caller (`AuthController#createApiKey`'s explicit-`tenantId` branch — the only
    endpoint shared by a self-service and a cross-tenant caller, so the authorization split can
    only live in code, never a URL-pattern rule). **Deliberately NOT wired into
    `tenant.domain.TenantAdminService#create`** (`POST /api/v1/admin/tenants` — no `{id}`, so not
    literally the brief's `/admin/tenants/{id}/...` wording either): that whole path is already
    `platform:admin`-exclusive at the HTTP boundary with no other caller, so an in-service
    re-check would be pure redundancy — and would have broken `TenantAdminServiceTest`'s
    established no-`SecurityContext` service-level tests (this codebase's convention: domain
    services stay auth-agnostic, `*GateTest` classes cover the HTTP gate). Judgment call recorded
    on that class's own Javadoc, not silently decided.
  - **Contract:** `docs/api/openapi.json` regenerated via `OpenApiContractTest`'s own mechanism —
    additive/description-text-only diff (38 insertions / 38 deletions), no path or shape change;
    every `"Requires the admin scope"` string became its granular equivalent. This is flagged as a
    **breaking change to scope semantics by design** (spec V3) in the PR body, not a silent
    behavior change.
  - **Tests (17 new):** `ScopeRegistry`-backed updates across every existing scope-gate test
    family (`SchemaManagementScopeGateTest`, `ConsumingPartyAdminGateTest`, `TenantAdminGateTest`,
    `ActivityAttentionScopeGateTest`, `StatsScopeGateTest`, `CredentialListScopeGateTest`,
    `AuthLoginCycleTest`) plus new `AdminApiKeyEndpointTest` cases proving the D4 gap is closed
    (tenant:admin-only cross-tenant mint → 403 + zero rows created, verified under the *target*
    tenant's own RLS context so the assertion can't pass vacuously; platform:admin cross-tenant
    mint → 200 + `ON_BEHALF_OF` audit row).
  - **DoD:** `mvn verify` green (344/344); live compose e2e against the rebuilt image (real
    Postgres, `V10` applied cleanly against the existing dev volume) — PLATFORM_ADMIN session →
    `/admin/tenants` 200; `schema:manage`-only key → schema create 200, `/admin/tenants` 403;
    `consumer:manage`-only key → consuming-party create 200, `/admin/tenants` 403;
    `tenant:admin`-only key → `/admin/tenants` 403 and cross-tenant key mint 403; PLATFORM_ADMIN
    session → cross-tenant key mint 200 with `ON_BEHALF_OF` audit row confirmed in `audit_log`.
    `CrossTenantIsolationTest`/`ModulithBoundariesTest` green throughout.
- **chore/credential-search-status-filter — server-side status filter on credential search**
  (session `chore/credential-search-status-filter`, 2026-07-28): closes the console's recorded
  platform ask (`khatm-console` `docs/STATE.md`, 2026-07-28, C6b chore — logged there, now marked
  addressed-pending-merge via a small cross-repo doc PR, see below). `mvn verify` green,
  **329/329 tests (9 new)**. **PR #41 opened, NOT merged** — pending Majd's review. No new
  `ErrorCode`/message key (invalid `status` values reuse the existing `KH-SYS-0400
  /validation.failed`), so no Arabic-review gate.
  - **Verify-first finding (per the brief):** confirmed lifecycle status is fully *derived*, never
    stored — `credential.domain.CredentialStatus#derive(Credential, Instant)` (added KH-1.6-BE),
    reading `revoked`/`usesRemaining`/`validTo` with precedence `REVOKED` > `EXHAUSTED` >
    `EXPIRED` > `ACTIVE`. `EXPIRED` is indeed time-derived (`validTo` vs. a caller-supplied
    `Instant`), confirming the brief's hint — this is exactly what makes the single-shared-instant
    design below necessary.
  - **Server-side filter, single source of derivation:** `CredentialRepository#search` gained an
    inline JPQL `CASE WHEN c.revoked ... WHEN c.usesRemaining <= 0 ... WHEN c.validTo < :now ...
    ELSE 'ACTIVE' END IN :statuses` clause — the SQL mirror of `CredentialStatus#derive`'s exact
    precedence, cross-referenced in both classes' Javadoc so a future precedence change can't
    update one without the other. `CredentialService#search` now captures one `Instant now` and
    passes it to **both** the repository call and each row's own `toSummary(c, now)` status
    derivation — the same instant, not two independent `Instant.now()` calls — which is what
    actually *guarantees* (not just usually-true) that a row can never show a status it was just
    filtered out of. "No filter requested" resolves to *every* `CredentialStatus` name rather than
    a `null`/empty collection, sidestepping Hibernate's `IN`-clause-with-null/empty-list edge cases
    entirely and keeping "no filter" and "every status selected" the same code path.
  - **A real, unanticipated constraint:** an `EXPIRED` test fixture cannot be issued directly with
    a negative `validMinutes` — `credential`'s own `CHECK (valid_to > valid_from)` (V1 baseline)
    rejects an already-inverted window at INSERT time (this constraint also binds UPDATEs, so it
    can't be worked around by moving only `valid_to` backward afterward either). Fixed by issuing
    normally then backdating *both* `valid_from` and `valid_to` together via a direct SQL `UPDATE`
    in the test fixture helper, preserving the CHECK while landing `valid_to` safely behind `now()`.
  - **Tests (9 new):** `credential.domain.CredentialSearchStatusFilterTest` (7 — each reachable
    status filters in isolation, multi-value OR, no-filter-returns-everything, the EXPIRED boundary
    just-past/just-future, status filter composed with pagination, invalid value throws
    `ValidationException`, and a single-source-of-derivation regression asserting a status filter's
    result set always exactly equals the rows the same unfiltered call's own `status` field reports
    for that status), `rbac.CredentialListScopeGateTest` (+2 — real HTTP repeated-`status=`-param
    binding end-to-end, and the `KH-SYS-0400` 400 envelope shape for an invalid value).
  - **Docs:** `docs/api/openapi.json` regenerated (additive-only — one new query param + one new
    400 response on `GET /api/v1/credentials`, confirmed via `git diff`); `docs/error-codes.md` and
    both message bundles **unchanged** (confirmed via their own tests passing with zero diff).
  - **Cross-repo STATE update:** `khatm-console` (checked out locally at
    `C:\Projects\KHATM-Project\khatm-console`) is a separate repository this session also touched,
    on its own small chore branch (`chore/state-platform-ask-pr41`), to mark the ask this session
    closes as addressed-pending-merge (not fully closed yet, since PR #41 itself isn't merged) —
    **`khatm-console` PR #18 opened, not merged**. Explicitly told not to run that repo's `npm run
    contract:update` until #41 lands on this repo's `main`.
  - **Proactive gitleaks check:** ran a local unredacted gitleaks scan
    (`docker run zricethezav/gitleaks:latest detect --redact=0`) against this branch's commit
    before opening the PR — clean — a habit picked up from the KH-1.6-BE session's false-positive
    incident (see that entry below), rather than discovering a CI failure after the fact.
- **KH-1.6-BE — Consumption Lifecycle Visibility** (session `feat/KH-1.6-BE-consumption-lifecycle`,
  2026-07-27, spec `docs/specs/FS-1.6-consumption-lifecycle-visibility.md`, veto resolutions V1–V3
  already resolved in the spec itself): `mvn verify` green, **320/320 tests (8 new)**. Live compose
  e2e run for real (DoD): issue `maxUses=2` → consume ×2 (2nd returns `remaining=0`) → 3rd rejected
  (`already_consumed`) → `holder-status` shows `EXHAUSTED 0/2` → `/verify` returns `valid:false`
  `reason:exhausted` → status-list bit (idx 7, MSB-first decode against the live artifact) reads
  set → search row shows `status:EXHAUSTED, usesConsumed:2`. **DONE & MERGED via PR #39**
  (2026-07-28, merge commit `9223a63`, fast-forward); branch `feat/KH-1.6-BE-consumption-lifecycle`
  deleted.
  - **Verify-against-code findings (recorded before writing, per the brief):** `Credential` had no
    status-like column at all — D1 needed no migration, since the exactly-once `EXHAUSTED`
    transition falls out for free from `CredentialRepository#consumeOne`'s existing atomic `WHERE
    uses_remaining > 0` UPDATE (only the one call that decrements 1→0 ever observes 0 afterward, in
    its own transaction — no new guard column). `ConsumeResponse` already carried `usesRemaining`
    from an earlier session — nothing to add there. Revocation's exact bit-flip/republish path
    (`status.api.StatusListRevoker#revoke`, called from `CredentialService#revoke`) is what D1/D2
    reuse verbatim from `AtomicConsumptionRecorder#tryConsume`, in the same transaction as the
    decrement.
  - **D1 — exactly-once `EXHAUSTED` transition:** new module-private `credential.domain
    .CredentialStatus` enum (`ACTIVE/EXHAUSTED/REVOKED/SUSPENDED/EXPIRED`), derived at read time
    from `revoked`/`usesRemaining`/`validTo` — precedence `REVOKED` > `EXHAUSTED` > `EXPIRED` >
    `ACTIVE`. `SUSPENDED` is part of the published vocabulary for forward contract stability but is
    **not reachable by any code path today** — KH-2.1's tenant suspension deliberately does not
    affect already-issued credentials' verify/consume/status-list serving, and nothing else
    suspends an individual credential; documented on the enum's own Javadoc, revisit only if a
    future session adds a credential/schema-level suspension mechanism.
    `AtomicConsumptionRecorder#tryConsume` re-reads the credential row right after its own
    successful decrement (same transaction); if `usesRemaining == 0`, calls
    `StatusListRevoker#revoke` and records new `AuditAction.CREDENTIAL_EXHAUSTED` — both exactly
    once, by construction (every later `consumeOne` against an already-0 row fails the WHERE clause
    and never reaches this code at all). New `db.ConcurrentConsumeTest` case: `maxUses=5`, 6
    concurrent callers → exactly 5 succeed, `uses_remaining=0`, exactly one
    `CREDENTIAL_EXHAUSTED` audit row, `status_list.version` bumped by exactly 1 from its
    pre-consume baseline.
  - **D2 — status-list bit flip, reused path:** no new bit-flip mechanism — D1's
    `StatusListRevoker#revoke` call above *is* D2. New `status.domain
    .CredentialExhaustionStatusListTest` (lives in `status.domain`, not `credential.domain`,
    specifically to reach package-private `BitstringCodec` — "live-code authority": decodes the
    published artifact's bit with the exact same MSB-first logic production uses, not a
    second/possibly-divergent reimplementation): issue `maxUses=1` → consume once → publish →
    assert the bit reads set, mirroring `StatusListPublishTest`'s existing revoke regression.
  - **D3 — `POST /api/v1/credentials/holder-status`, public, proof-of-possession** (a deliberate,
    explicit reversal of PR #33's original "no live uses-remaining channel" stance — recorded here
    per spec V1's own instruction so this isn't misread later as an unintended contradiction; PR
    #33 was right for its own moment, this session's spec explicitly revisits and reverses it with
    Majd's sign-off, see FS-1.6 §2 V1): body `{"jwt": "<bare compact SD-JWT>"}` (no disclosures —
    only proof of signature possession, never claim content, P1 rule); response `{status, maxUses,
    usesRemaining, lastConsumedAt?}`. `CredentialService#holderStatus` reuses `#checkSignature` and
    `CredentialRepository#findByRef` verbatim (no second implementation) — malformed JWT, bad
    signature, and unknown `ref` all collapse to the same reused `KH_CRD_0404` (anti-enumeration,
    same collapsing judgment call `KH_CLM_0404` already made; no new `ErrorCode` needed). Wrapped in
    `SystemAccessExecutor#runAsSystem` by the controller, the identical shape `/verify` already
    uses — no new entry needed in `SystemAccessCallerAllowlistTest`'s enumeration since
    `CredentialController.java` was already in it (as "verify lookup"). New `SecurityConfig`
    `permitAll` entry (now six public endpoints, Javadoc updated) + new `rbac
    .PublicEndpointsNoCredentialsTest` case (the "public path list test" the brief pointed at). New
    `ConsumptionEventRepository#findTopByCredentialIdOrderByConsumedAtDesc` for `lastConsumedAt`.
    New `credential.domain.HolderStatusTest` (5 cases: active/exhausted-with-timestamp/revoked/
    malformed-404/tampered-signature-404).
  - **D4 — new `VerifyReason.EXHAUSTED`:** checked in `CredentialService#verify` right after the
    existing `REVOKED` branch, before the disclosure-shape checks — `200 valid:false
    reason:exhausted`. New `verify.reason.exhausted` key, both bundles, same commit (Arabic-review
    gate applies to this session's PR).
  - **D5 — additive `status`/`usesConsumed` on search + detail:** `CredentialSummary` (search rows)
    and `CredentialView` (`GET /{id}`) both gained `status` (the same `CredentialStatus` string) and
    `usesConsumed` (`maxUses - usesRemaining`) fields — populated in `CredentialMapper#toView` and
    `CredentialService#toSummary`. Purely additive; both existing construction call sites updated,
    no other caller in the codebase constructs these records directly.
  - **D6 — docs:** `docs/api/openapi.json` regenerated via `OpenApiContractTest`'s own debug-dump
    mechanism (95 insertions, 0 deletions — additive-only, confirmed via `git diff --stat`).
    `docs/error-codes.md` **unchanged** — no new `ErrorCode` this session (holder-status reuses
    `KH_CRD_0404`). `MessageBundleParityTest` green throughout.
  - **STATE sweep (recorded at PR-open time):** the previous entry below claimed
    `chore/KH-2.1-review-followups` "PR opened, not yet merged" — `git log` at this session's start
    already showed it merged (PR #38, merge commit `8d6a927`, which is `origin/main`'s tip this
    branch was cut from); corrected below, same "confirm main's actual state via git log, don't
    trust a stale STATE note" pattern KH-1.4.4-BE/KH-1.1.3-BE/KH-2.1-BE sessions already established.
  - **Pre-merge CI fix — gitleaks false positive, real (not skipped):** PR #39's own `gitleaks
    (secrets)` check failed on every push, red on an otherwise fully green PR (Build/verify, Trivy,
    compose-smoke all passed). Confirmed a genuine false positive by running gitleaks locally
    unredacted (`docker run zricethezav/gitleaks:latest detect ... --redact=0`) against the exact
    commit range CI scans: the `generic-api-key` rule's trigger word "token" appeared a few
    characters before a 20-char unbroken run — `unresolvable/retired`, a plain English phrase in
    `CredentialService#holderStatus`'s Javadoc (no `/`-joined identifier is anywhere near a real
    secret in this codebase; the slash alone was enough to keep the run unbroken past the rule's
    20-char/3.5-entropy threshold). **Fix chosen over allowlisting**: reworded the sentence
    ("a malformed JWT, an unresolvable or retired `kid`") to break the run with spaces — a smaller,
    safer change than adding a permanent `.gitleaks.toml` allowlist entry for a common English
    phrase pattern that could otherwise mask a real future finding using similar wording. **Because
    gitleaks scans the PR's commit-by-commit diff, not just the final tree**, a fix-up commit alone
    would not have cleared it — the bad phrasing was already baked into an already-pushed commit's
    diff within the scanned range. Majd chose (offered two options) to squash the branch's three
    commits into one clean commit with the reworded Javadoc already applied, `--force-with-lease`
    push it (branch was solo/unshared — low risk), and let CI re-run clean before merging, rather
    than merging over the red check. Verified locally with the same dockerized gitleaks scan before
    *and* after the force-push (`no leaks found`) — not just trusted to CI. New CI run (all 4 checks
    green) confirmed before merge.
- **chore/KH-2.1-review-followups — post-merge review actions for KH-2.1-BE** (session
  `chore/KH-2.1-review-followups`, 2026-07-27): four follow-ups from KH-2.1-BE's review (PR #36,
  merged), `mvn verify` green, **316/316 tests (8 new)**. No contract change (additive-only
  confirmed via `OpenApiContractTest`), no message-bundle change — no Arabic-review gate this
  session.
  1. **`docs/CONVENTIONS.md §5` amended** (explicit approval this session) to codify the
     repository-transactional exception KH-2.1-BE's bug-4 fix introduced, replacing the old
     absolute "never repositories" line — see `docs/CONVENTIONS.md §5` for the final wording. The
     "known, deliberate discrepancy" note this created in the KH-2.1-BE writeup below, and the
     corresponding stale "Next up" item, are both removed.
  2. **`TenantContextFilter` coverage — proven, not assumed** (the review's main concern):
     - **Fail-fast guard**: `TenantContext.current()`/`currentSlug()` now throw
       `IllegalStateException` (→ generic 500, no new `ErrorCode`/message key — reuses the
       existing unhandled-exception path) when `SecurityContextHolder` holds a real, authenticated,
       non-anonymous principal but nothing was ever `set` on the thread — the "filter got
       bypassed" shape. The default-tenant fallback stays legal for the five genuinely anonymous
       HTTP paths, `SystemAccessExecutor`-wrapped worker/lookup code, seeders, and tests, verified
       against the code path by path (see `TenantContext`'s class Javadoc).
     - **Self-inflicted regression caught before shipping**: the new guard initially broke ~210
       tests platform-wide. Root cause: `TenantContextTransactionExecutionListener#afterBegin`
       fires on every physical transaction, including the one `TenantContextFilter` itself uses
       internally to look up which tenant to `set` — at that exact moment a real principal is
       already on the `SecurityContext` but `TenantContext.set` hasn't run yet (resolving the
       tenant *is* the point of that lookup), which the new guard wrongly rejected as a bypass.
       Fixed with a new package-private, never-throwing
       `TenantContext#currentIdForTransactionPropagation()`, used only by that listener — plumbing,
       not an HTTP-authentication judgment call.
     - **Structural coverage proof**: new `rbac.security.TenantContextFilterCoverageTest` asserts,
       via `SecurityFilterChain#getFilters()` (public API), that `TenantContextFilter`'s index is
       after `ApiKeyAuthFilter`'s on the api-key chain and after `SecurityContextHolderFilter`'s on
       the session chain — a structural guarantee covering every route on either chain, present or
       future, not a sampled route list. Chosen over a `MockMvc` route-enumeration sweep (would
       duplicate `SecurityConfig`'s private path constants and go stale as routes are added) — see
       the test's own Javadoc for the full rationale. Note: no pre-existing "public-path-list test"
       was found in the codebase to reuse as a shared source of truth, despite a thorough search;
       this test stands alone.
     - **Guard regression test**: new `shared.TenantContextFailFastGuardTest` (5 cases) —
       authenticated-principal-plus-unset-context throws for both `current()`/`currentSlug()`;
       anonymous/no-authentication/explicitly-set-context all still fall back or return correctly,
       never throwing.
  3. **Bug-7 aftermath — `V9__resign_status_lists.sql`**: confirmed by reading
     `status.domain.StatusListPublisher#publishIfStale` that it only republishes when
     `artifact_version < version`, so a pre-fix, wrongly-signed-but-version-current status list
     (from KH-2.1-BE bug 7, the sweep-signing bug) would never be re-signed by any future sweep
     tick, worker restart, or upgrade — republish is not otherwise guaranteed. New append-only,
     data-only migration bumps every `status_list.version` by one, forcing exactly one re-sign per
     list on the next sweep tick with the now-correct per-tenant key (a list that was always
     correctly signed just gets one harmless extra re-sign). Regression test added to
     `StatusListPublishTest` proving a version-bump-alone is sufficient to make an
     already-current artifact look stale again and trigger a real republish.
  4. **V7 `tenant_id` backfill — verified, not a constant, no fix needed**: read the applied
     migration directly — `consuming_party_schema.tenant_id` backfills from
     `consuming_party.tenant_id` via `UPDATE ... FROM consuming_party cp WHERE cp.id =
     cps.consuming_party_id`; `user_role.tenant_id` backfills from `app_user.tenant_id` via
     `UPDATE ... FROM app_user au WHERE au.id = ur.user_id`. Both derive from the parent row
     through an explicit join, confirming the review's concern did not materialize.
  - V1–V8 untouched, `MigrationImmutabilityTest` green; `V9`'s checksum appended to
    `db/migration-checksums.lock`.
  - **Branch `chore/KH-2.1-review-followups` — DONE & MERGED via PR #38** (merge commit
    `8d6a927`), confirmed via `git log` at this session's start (see the STATE-sweep note in the
    KH-1.6-BE entry above).
- **KH-2.1-BE — Multi-Tenancy Core** (session `feat/KH-2.1-BE-multi-tenancy-core`, 2026-07-27,
  spec `docs/specs/FS-2.1-multi-tenancy-core.md`): full multi-tenancy — tenant context resolution,
  a tenant admin/onboarding plane, per-tenant trust endpoints, and real Postgres Row Level
  Security enforcement. `mvn verify` green, **308/308 tests (38 new)**. Two parts, one session,
  separated by the spec's own hard checkpoint:
  - **Part A** (D1, D6–D9, no RLS yet): `shared.TenantContext` became `ThreadLocal`-backed
    (`set`/`clear`/`current`/`currentSlug`, falling back to the default tenant when unset — zero
    call-site changes needed anywhere). New `tenant.api`/`tenant.domain`/`tenant.web` — the
    onboarding plane (`POST /api/v1/admin/tenants`, resumable-create design, spec V3: a slug with a
    tenant row but no `ACTIVE` key yet resumes instead of conflicting — no `KH-TNT-0422` needed).
    New narrow cross-module surfaces `key.api.TenantKeyProvisioner`/`JwksLookup` and
    `status.api.StatusListAllocator#ensureList`/`StatusListLookup#findArtifact`. Per-tenant JWKS
    (`GET /t/{slug}/.well-known/jwks.json`) and status-list (`GET /sl/{slug}/{listCode}`, moved
    from `status.web` to `tenant.web`) endpoints — the relocation avoids a Modulith cycle
    (`tenant` depends one-way on `key`/`status :: api` for onboarding). Suspended-tenant
    enforcement blocks issuance/login/new-sessions only (spec V4) — verify/consume/status-list/JWKS
    keep serving a suspended tenant's already-issued credentials. Legacy `/.well-known/jwks.json`
    stays as a deprecated default-tenant alias (spec V2), zero code change. Committed standalone
    as `5819fd3` before Part B started, per the spec's hard checkpoint.
  - **Part B** (D2–D5, D10, real RLS): `V7__rls_policies.sql` — `FORCE ROW LEVEL SECURITY` +
    `tenant_isolation`/`system_access` PERMISSIVE policies on 14 business tables (backfilled
    `tenant_id` onto two join tables, `consuming_party_schema`/`user_role`, that had none), a
    locked-down `khatm_app` DB role (no `BYPASSRLS`, not table owner), transaction-scoped
    `app.tenant_id` propagation (`shared.TenantContextTransactionExecutionListener`, registered on
    the app's `JpaTransactionManager`), and `shared.SystemAccessExecutor` for the enumerated
    anonymous-principal read paths. Mandatory `db.CrossTenantIsolationTest` (HTTP-layer 404,
    repository-layer RLS-not-app-code proof, missing-context closed-fail).
    **Bugs found and fixed along the way, all confirmed real (not test-only) and RLS-caused:**
    1. `TenantAdminService#create`'s `hasActiveKey` conflict check ran under the *calling admin's*
       ambient tenant, not the target — RLS hid the target's own key, so a genuine duplicate-slug
       conflict silently fell through to the resume path instead of throwing `KH-TNT-0409`.
    2. `ApiKeyService#create(..., UUID tenantId)` (the tenant-admin-plane overload minting a key for
       a tenant other than the caller's own) inserted under the wrong ambient `app.tenant_id` for
       the same reason — fixed with the same explicit `TenantContext.set(tenantId, slug)` pattern.
    3. `ApiKeyService#verify` — API-key verification is, by construction, a lookup with no tenant
       known yet (resolving it is the point), so it can never rely on ambient `TenantContext` the
       way this class's other methods do; a key for any non-default tenant was invisible. Now runs
       under `SystemAccessExecutor` (added to its enumeration).
    4. **The big one:** Spring Data JPA derived-query methods are only transactional when called
       from inside another `@Transactional` method — invoked bare (deliberately, for unrelated
       reasons, at a handful of real production call sites, plus dozens of test "call service, then
       verify via a bare repository/`jdbc` call" assertions), they run with no Spring-managed
       transaction, so the `app.tenant_id` listener never fires and RLS closed-fails to zero rows
       regardless of the real data. This silently turned `credential.domain.CredentialService
       #enforceSchemaAllowlist`'s "can't resolve this schema, don't block" fallback into "can never
       resolve any schema, always allow" — **a real authorization bypass**, caught by
       `rbac.ConsumeApiKeyGateTest` (expected 403, got 200). Fixed platform-wide: every
       `JpaRepository` interface now carries a type-level `@Transactional(readOnly = true)` (a
       no-op wherever a method already has its own more-specific annotation or an ambient
       transaction — lowest priority in Spring's lookup order), with an explicit bare
       `@Transactional` override on every `@Modifying` method; `db.RepositoryDefaultTransactionsTest`
       pins both as a structural invariant, and `enforceSchemaAllowlist`'s fallback is now
       deny-by-default on principle (new `consumer.schema-unresolvable` messageKey, same
       `KH-CNS-0403` code), not just because the underlying bug is fixed. Decision + all four riders
       (structural test, deny-by-default flip, this writeup) made with Majd + a plan-mode architect
       review mid-session — see git history for the exact exchange.
    5. `TransactionalTestJdbcTemplateConfig` (test-only, wraps the shared `JdbcTemplate` bean so
       bare test verification calls get a transaction) originally used `REQUIRES_NEW`, which
       suspends and cannot see an *ambient* test-method transaction's own uncommitted JPA writes
       (`ClaimCodeExpirySweepTest` et al., which deliberately wrap the whole test method in one
       transaction for unrelated reasons) — changed to `REQUIRED`, correct for both cases.
    **Three more bugs found only by the live compose e2e run (3 real tenants, real Postgres) — none
    of these surfaced in the Testcontainers-backed suite, which is why the DoD requires the e2e
    step at all, not just `mvn verify`:**
    6. `event_publication` (Spring Modulith's own JDBC event-publication registry, `V1__baseline.sql`
       §3.12) never got a `khatm_app` grant — `V7`'s grant loop only covered the 14 RLS-protected
       business tables plus the one documented RLS exclusion (`tenant`), missing this table
       entirely. Every event-publishing request (i.e. every credential issuance) failed with
       "permission denied for table event_publication" — reproduced against both an existing
       pre-KH-2.1 volume and a genuinely fresh one, so this is a universal gap, not an
       upgrade-path-only one. New `V8__event_publication_grants.sql` (append-only, per CLAUDE.md —
       `V7` was already applied nowhere outside this session, but the rule is the rule).
    7. `status.worker.StatusListPublishSweepWorker` runs its whole tick under
       `SystemAccessExecutor` (correctly, so `findStaleIds` sees every tenant's stale lists in one
       query) but never set `TenantContext` to each individual list's own tenant before signing it —
       `key.domain.KeySignerImpl` reads only the ambient `TenantContext`, so every list the sweep
       touched was signed with whichever tenant happened to be ambient for the scheduled worker
       thread (the platform default, in practice), regardless of which tenant actually owned it. A
       wallet verifying a non-default tenant's status list against that tenant's own JWKS would
       always fail signature verification. `StatusListRepository#findStaleIds` widened to
       `findStaleRefs()` (new `StaleStatusListRef(id, tenantId)` projection) so the sweep can wrap
       each list's publish in `TenantContext.set(ref.tenantId(), "")`. Regression test in
       `StatusListPublishTest` provisions a second tenant and asserts the published artifact's JWS
       `kid` matches that tenant's own key.
    8. `CredentialService#verify` and `ClaimRedemptionService#redeem` both run under
       `SystemAccessExecutor` (anonymous, no ambient tenant) and both independently re-derive a
       `statusListUri` response field via `StatusListLookup#findRef` →
       `status.domain.StatusListUriBuilder`, which builds the `/sl/{tenantSlug}/...` path from
       `TenantContext.currentSlug()` — always the platform default for these two anonymous paths,
       regardless of which tenant actually issued the credential. (The credential's own *embedded*
       JWT claim was always correct, since `#issue` runs under the issuing tenant's authenticated
       context — only this separately-rebuilt convenience field was wrong.) Fixed by resolving the
       credential's own tenant's slug via `tenant.api.TenantDirectory` (new `credential → tenant ::
       api` dependency edge — safe, `tenant` has no reverse dependency on `credential`) and wrapping
       the `findRef` call in `TenantContext.set(credential.getTenantId(), slug)` in both services.
       Regression test added to `db.CrossTenantIsolationTest`.
    **DoD status:** `mvn verify` green (308/308); live compose e2e (3 tenants, `e2e-alpha`/
    `e2e-alpha2`/`e2e-beta`/`e2e-beta2` across a fresh-volume run and an existing-pre-KH-2.1-volume
    upgrade run) — **done**, full sequence (onboard → key → issue → per-tenant JWKS/status-list →
    cross-tenant 404 → suspend blocks issuance while verify/status-list/JWKS keep serving → reactivate
    restores issuance) passing on the final image. **DONE & MERGED via PR #36** (2026-07-27,
    merge commit `d6ae42c`, fast-forward, Arabic-review gate cleared); branch
    `feat/KH-2.1-BE-multi-tenancy-core` deleted.
    **Also updated `docs/deploy-staging.md`** with the `khatm_app` role provisioning requirement
    (fresh-host compose snippet + a one-time manual-SQL step for an existing pre-KH-2.1 deployment,
    since `docker-entrypoint-initdb.d` only runs against an empty data directory).
- **KH-1.1.5-BE — Dashboard v2 read endpoints** (session `feat/KH-1.1.5-BE-dashboard-stats-v2`,
  2026-07-25, spec `docs/specs/FS-1.5.4-dashboard-stats-v2.md`): added `GET /api/v1/stats/daily`,
  `GET /api/v1/activity`, `GET /api/v1/attention`, `GET /api/v1/admin/signing-keys`, and
  `GET /api/v1/stats/consuming-parties` — unblocks the console's four Dashboard v2 panels. New
  `rbac :: api` surface `ApiKeyOwnerLookup` resolves historical `audit_log.actor_id` to its owning
  consuming party. `mvn verify` green, **274/274 tests (38 new)**. See the spec doc for full design
  detail (module placement, D1–D9).
- **chore/redeem-uses-metadata — holder-facing uses/validity metadata on redeem** (session
  `chore/redeem-uses-metadata`, 2026-07-24, merged via PR #33): micro-session, gap confirmed from
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
- 2026-07-28: KH-2.2a-BE — RBAC scope registry (D1–D4): nine-scope deny-by-default registry
  replaces the coarse `admin` scope; every `/api/v1/admin/**` endpoint re-gated per family; found
  and closed a real cross-tenant gap in cross-tenant API-key minting via new
  `shared.OnBehalfOfExecutor`. `mvn verify` green, 344/344 tests (17 new). **PR #43 opened, NOT
  merged.** See "Current phase / task" above for the full D1–D4 breakdown.
- 2026-07-28: chore/credential-search-status-filter — server-side `status` query param on `GET
  /api/v1/credentials`, closing the console's recorded C6b platform ask. `mvn verify` green,
  329/329 tests (9 new). **PR #41 opened, NOT merged.** Also opened `khatm-console` PR #18
  (docs-only, not merged) marking that ask addressed-pending-merge. See "Current phase / task"
  above for the full breakdown (single-shared-instant filter design, the `credential_check` CHECK
  constraint finding, and the proactive gitleaks scan).
- 2026-07-28: KH-1.6-BE — Consumption Lifecycle Visibility (D1–D6). `mvn verify` green, 320/320
  tests (8 new); live compose e2e run for real end-to-end. **DONE & MERGED via PR #39**
  (2026-07-28, merge commit `9223a63`, fast-forward); branch
  `feat/KH-1.6-BE-consumption-lifecycle` deleted, merged on Majd's explicit instruction. **Note:**
  no separate Arabic-wording review pass was surfaced as a distinct step this session for the new
  `verify.reason.exhausted` key before merge — flag for a follow-up read if that matters, unlike
  prior sessions' recorded "confirmed by Majd, no wording changes" pattern. See "Current phase /
  task" above for the full D1–D6 breakdown, verify-against-code findings, and the pre-merge
  gitleaks false-positive fix (branch squashed + force-pushed to clear a stale finding from an
  already-pushed commit's diff, CI reconfirmed green before merge).
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
- Default tenant strategy: **KH-2.1 landed real multi-tenancy** (tenant admin/onboarding plane +
  Postgres RLS) — the original fixed default-tenant UUID
  `00000000-0000-0000-0000-000000000001` (seeded by `V1__baseline.sql`, mirrored as
  `sy.khatm.platform.shared.TenantContext.DEFAULT_TENANT_ID`) still exists and still works as the
  fallback every pre-KH-2.1 code path (seeders, the legacy default-tenant JWKS alias, any caller
  that never touches tenant machinery) resolves to when nothing set an explicit tenant context —
  it just isn't the *only* tenant anymore.
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
hardening, versioned published contract — see "Current phase / task" above), support mode closed
out a first wave (KH-1.1-BE schema management + credential search + the consume idempotency race;
KH-1.4.4-BE the consuming-party admin plane + closed the `ensure()` race; KH-1.1.3-BE bulk issuance
+ the stats endpoint + OpenAPI security schemes; KH-1.1.5-BE Dashboard v2's five read endpoints,
merged via PR #35), **KH-2.1-BE (multi-tenancy core + real Postgres RLS) merged via PR #36** with
its review follow-ups merged via PR #38, **KH-1.6-BE (consumption lifecycle visibility —
`EXHAUSTED` status, holder-status endpoint) merged via PR #39**,
**chore/credential-search-status-filter (server-side `status` filter, closing the console's C6b
ask) built and verified, PR #41 opened, not yet merged**, and **KH-2.2a-BE (RBAC scope registry,
D1–D4) built and verified, PR #43 opened, not yet merged** — the two outstanding `khatm-platform`
PRs as of this update (plus a small docs-only `khatm-console` PR #18 marking the C6b ask
addressed-pending-merge).

0. **KH-2.2b-BE — next planned session** (spec `docs/specs/FS-2.2-rbac-granularity.md` §3, D5+D6
   +D8): the tenant user-management surface (`GET/POST /api/v1/users`, roles/lock/unlock/disable
   /reset-password, `tenant:admin`-gated), onboarding completion (`initialAdmin` on tenant create +
   `POST /admin/tenants/{id}/users`, spec D4's on-behalf-of pattern for real this time — these
   {id}-suffixed endpoints genuinely will touch tenant-scoped RLS data, unlike anything
   `shared.OnBehalfOfExecutor` covers today), and the last-tenant-admin guard (`KH-USR-0423`,
   `ConcurrentLastAdminTest`). Depends on KH-2.2a-BE (PR #43) merging first — its scope registry is
   what D5's `tenant:admin` gate and D8's new `KH-USR-*` error codes/Arabic keys build on.
1. **C6 (console) / W4 (wallet) — unblocked, KH-1.6-BE is merged**: the two follow-on session
   briefs spec `docs/specs/FS-1.6-consumption-lifecycle-visibility.md` §"Brief — C6"/"Brief — W4"
   already scope in full — console credential-lifecycle badges/uses-column/filter and wallet's live
   holder-status refresh + exhausted-vs-revoked verifier distinction. Both self-stop if a contract
   field they need is somehow absent, but the contract now carries everything both briefs ask for.
   **C6b's own status-filter-dropdown follow-up** (khatm-console, self-stopped 2026-07-28 on the
   missing `status` param) is what PR #41 above unblocks — still needs PR #41 merged, then
   khatm-console's own `npm run contract:update` re-run, before the dropdown itself can be built.
2. **Console's four Dashboard v2 panels (other repo)** — now that KH-1.1.5-BE is merged, wiring the
   console side to real data is the already-scoped follow-up this session's brief named
   (khatm-console's `docs/STATE.md`, "Next up" #5).
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
