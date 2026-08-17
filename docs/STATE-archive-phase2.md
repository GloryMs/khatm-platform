# Archive date:2026-08-17
> التاريخ الأقدم: docs/STATE-archive-phase0.md

# Prev Tasks (moved from "Current phase / task")
- Phase 0 — Production Foundation, fully closed (see prior sessions).
- **feat/KH-2.2d-BE-multitenant-login — closes the two platform gaps khatm-console recorded
  against FS-2.2's exit walkthrough** (session `feat/KH-2.2d-BE-multitenant-login`, 2026-07-30,
  spec `docs/specs/FS-2.2-rbac-granularity.md`, Majd's explicit decision this session: login
  tenant discrimination via an optional tenant slug, backward compatible). `mvn verify` green,
  **381/381 tests (7 new)**. No new `ErrorCode`/message key (an unknown/`SUSPENDED` `tenantSlug`
  reuses `KH-RBC-0401`/`error.rbc.unauthenticated`; the new `GET` endpoint reuses
  `KH-RBC-0403`/`KH-TNT-0404`), so no Arabic-review gate.
    - **The two gaps, both closed:**
        1. `POST /api/v1/auth/login` could only ever authenticate against the ambient default tenant
           (`AuthService#login` read `TenantContext.current()`, which for an anonymous request always
           falls back to default) — documented as an explicit out-of-scope caveat on
           `SuspendedTenantAuthTest`'s own Javadoc since KH-2.2b. Now takes an optional `tenantSlug`;
           blank/absent still resolves to the default tenant, byte-for-byte the same as before.
        2. No way for a platform admin to list an *existing* tenant's users (only create was wired,
           KH-2.2b D6) — new `GET /api/v1/admin/tenants/{id}/users`.
    - **Verify-against-code finding that shaped the whole design (recorded before writing, per the
      brief):** `AuthService#login` was one `@Transactional(noRollbackFor = ...)` method spanning
      the entire check-then-audit sequence. `shared.TenantContextTransactionExecutionListener
    #afterBegin` fires exactly once per *physical* transaction and reads whatever
      `shared.TenantContext` holds **at that moment** — a single outer `@Transactional` boundary
      begins (and fires the listener) before any method body code runs, i.e. before the tenant could
      even be resolved from the submitted slug, permanently pinning `app.tenant_id` to the caller's
      ambient default for the rest of that transaction regardless of any later `TenantContext.set`
      call. Restructured to the exact shape `rbac.domain.ApiKeyService#create(.., UUID)` /
      `tenant.domain.TenantAdminService#create` already established for this same class of problem:
      `login` itself is no longer `@Transactional`; it resolves the tenant, calls
      `TenantContext.set`, then delegates to a private `authenticate` method whose calls
      (`AppUserRepository`'s type-level `@Transactional(readOnly = true)`,
      `AuditService#record`'s own `@Transactional`) each open their *own* fresh physical transaction
      that correctly picks up the just-switched tenant. This also **removed the need for
      `noRollbackFor` entirely** — each audit write now commits on its own before the method can
      throw, rather than relying on one shared transaction's rollback exemption.
    - **D1 — login, `rbac.domain.AuthService#login(username, rawPassword, tenantSlug)`:** blank/
      `null` `tenantSlug` resolves via `TenantContext.current()` (unchanged behavior); a non-blank
      one resolves via `TenantDirectory#findBySlug` — needs no ambient tenant context at all,
      `tenant` being the one business table excluded from RLS (spec FS-2.1 D2), so this works from a
      genuinely anonymous request with no chicken-and-egg problem. An unknown or `SUSPENDED` tenant
      gets the identical generic `KH-RBC-0401` every other failure reason gets (D7's anti-enumeration
      stance, extended: no tenant-existence oracle either). `rbac.domain.LoginResult` gained
      `tenantId`; `rbac.security.SessionAuthenticator#establish` now builds the session principal
      from it instead of `TenantContext.current()` (which, by the time the controller reads the
      login result, has already been cleared back to the anonymous request's default-tenant
      fallback — the literal bug `SessionAuthenticator` would have had if login had "worked" without
      this fix). Downstream — `rbac.security.TenantContextFilter`, RLS, the forced-password-change
      gate — needed **zero special-casing**, confirmed by the live walkthrough and by
      `SuspendedTenantAuthTest`'s new HTTP-level tests reaching `GET /api/v1/auth/me` successfully
      over a non-default tenant's session.
    - **D2 — `SuspendedTenantAuthTest`, extended to real HTTP, out-of-scope Javadoc removed:** the
      old `login_forSuspendedTenant_isRejected` (service-level, pointed `TenantContext` at a tenant
      directly since HTTP login couldn't reach a non-default tenant at all) replaced by three real
      HTTP tests: a freshly onboarded tenant's own admin, suspended, gets the generic 401 with
      correct credentials (`login_forSuspendedTenant_viaHttp_isRejected`); an unknown `tenantSlug`
      gets the identical failure (`login_forUnknownTenantSlug_isRejected_theSameGenericFailure`);
      and a full login → `GET /me` round trip against a non-default tenant succeeds and reflects
      that tenant's own user (`login_forNonDefaultTenant_viaHttp_establishesSessionScopedToThatTenant`).
    - **D3 — `GET /api/v1/admin/tenants/{id}/users`:** new
      `rbac.domain.TenantProvisioningService#listUsersInTenant`, the same `OnBehalfOfExecutor
    .runAsTenant` shape `createUserInTenant` already uses (no allowlist-test change needed —
      `shared.OnBehalfOfCallerAllowlistTest` enumerates by *file*, and
      `TenantProvisioningService.java` was already in it). New controller method on the existing
      `rbac.web.TenantProvisioningController`; inherits `platform:admin` for free from
      `SecurityConfig`'s existing `/api/v1/admin/tenants/**` wildcard rule — no `SecurityConfig`
      change needed. Returns the identical `UserSummary` row shape `GET /api/v1/users` returns.
    - **Contract:** `docs/api/openapi.json` regenerated via `OpenApiContractTest`'s own mechanism —
      additive-only (one new optional `tenantSlug` string on the login request body, one new `GET`
      operation, description-text-only change on the login summary; confirmed via `git diff`, no
      path/schema removed).
    - **Tests (7 new):** `rbac.SuspendedTenantAuthTest` (+2 net — replaced 1 service-level test with
      3 HTTP-level ones), `rbac.domain.TenantProvisioningServiceTest` (+1 —
      `listUsersInTenant_returnsTheNamedTenantsUsers_notTheCallersOwn`), `rbac.TenantAdminGateTest`
      (+3 — success, `tenant:admin`-but-not-`platform:admin` 403, unknown-tenant 404).
    - **DoD — live compose e2e, run for real, superseding KH-2.2b's default-tenant workaround note:**
      rebuilt `khatm-api`/`khatm-worker` images against the existing dev volume (V1–V12 already
      applied, confirmed via `docker logs`) — `POST /api/v1/admin/tenants` with `initialAdmin` →
      `POST /api/v1/auth/login` **with `tenantSlug`** + the one-time temporary password → 200,
      session established → `GET /me` shows `mustChangePassword:true` → `POST
    /api/v1/users/me/password` → `GET /me` shows `false` → `POST /api/v1/schemas` (own tenant) →
      `POST /{id}/publish` → `POST /api/v1/credentials/issue` (own tenant's published schema) → 200
      → `POST /api/v1/admin/consuming-parties` + `POST .../allowed-schemas` + `POST .../api-keys` →
      `POST /api/v1/credentials/consume` with the consuming-party key → `200 consumed:true` → a
      **second** tenant onboarded the same way, its own admin logged in (own forced-change cleared
      too) → `GET /api/v1/schemas` / `GET /api/v1/credentials` / `GET
    /api/v1/admin/consuming-parties` all return **zero** rows (`totalElements:0` on the paginated
      credential search) — real authenticated cross-tenant isolation evidence, not just an
      unauthenticated 403 — → tenant A's sole `TENANT_ADMIN` disabling itself → `409 KH-USR-0423` →
      `GET /api/v1/admin/tenants/{id}/users` (this session's new endpoint) confirms the one admin →
      tenant A suspended (`POST .../suspend`) → the same admin's login **with its correct password
      and `tenantSlug`** → generic `401 KH-RBC-0401`. One self-inflicted false start along the way,
      not a platform bug: `POST /issue`'s `schemaCode` is the literal find-or-create
      `credential_schema.code` (always version 1, spec FS-0.2's original quick-issue convenience),
      **not** `code/version` — using `"<code>/v1"` there silently auto-created a second,
      differently-`code`d schema row instead of hitting the one just authored+published, which is
      exactly why the first walkthrough attempt's `consume` call 403'd with `KH-CNS-0403
    consumer.schema-not-allowed` (the credential's real `schema_id` didn't match the one just
      allow-listed) — confirmed by reading `consuming_party_schema`/`credential`/`credential_schema`
      rows directly, not guessed; fixed by issuing with the bare `code`, no version suffix.
    - **DONE & MERGED via PR #48** (2026-07-30, merge commit `3a75e72`, merged on Majd's explicit
      instruction via admin override — no green CI run, same GitHub Actions billing block as PR #41/
      #43/#45/#46; `mvn verify` 381/381 run earlier in this session was the substitute gate). The fix
      is now on `main`; images rebuilt and the compose stack redeployed against it — see "Last
      completed" below.
- **chore/forced-change-discoverability — closes a real C7 (console) self-stop** (session
  `chore/forced-change-discoverability`, 2026-07-28): the console's Claude Code session for C7
  (spec FS-2.2 D7) self-stopped at its preamble gate — correctly, on inspection — because
  `KH-USR-0403` (the forced-password-change code KH-2.2b-BE shipped) was genuinely undiscoverable
  from the published contract: no endpoint documented it, `MeResponse` carried no flag, and
  `GET /api/v1/auth/me` — the one endpoint whose entire purpose is answering "who is this session
  and what's their status" — was itself blocked by `PasswordChangeEnforcementFilter` while the flag
  was set. A console could mint a temporary password but had no way to route a freshly-logged-in
  holder of one into a change-password screen without first eating an opaque 403. `mvn verify`
  green, 375/375 tests (0 new files — existing tests extended, no new behavior branch to cover).
  No new `ErrorCode` (`KH_USR_0403` already existed; it was a discoverability gap, not a missing
  code), so no Arabic-review gate.
    - **The actual fix, two parts:** (1) `GET /api/v1/auth/me` added to
      `PasswordChangeEnforcementFilter`'s exemption list — it is now the one place a client can read
      the state without being blocked by the very filter enforcing it. (2) `MeResponse` (and the
      domain-level `UserView` it's built from) gained a `mustChangePassword` boolean, populated from
      `AppUser#isMustChangePassword` via `AuthService#findUserView`. Together: login → `GET /me` →
      `mustChangePassword: true` → route to change screen → `POST /api/v1/users/me/password` → `GET
    /me` again → `false`. Confirmed via extended `rbac.UserAdminGateTest`
      (`temporaryPasswordLogin_...`) and `rbac.PasswordChangeEnforcementFilterExemptionTest`, both of
      which previously asserted `/me` as the *blocked* example and now assert it as the *discovery*
      path.
    - **`KH-USR-0403` also properly documented** on the 6 already-`tenant:admin`-gated
      `rbac.web.UserAdminController` operations (list/create/replaceRoles/lock/disable/reset-password)
      — merged into each operation's existing `403` response (OpenAPI has one entry per status code
      per operation), following the exact combining pattern `AuthController#createApiKey` already
      established for a `403` with more than one possible cause. Not spammed across all ~30
      operations that already selectively document `401`/`403` platform-wide — scoped to the literal
      surface a Users screen calls, where an admin whose own flag flips mid-session would actually hit
      it.
    - **Contract:** `docs/api/openapi.json` regenerated via `OpenApiContractTest`'s own mechanism —
      additive-only (one new boolean property on `MeResponse`; description-text-only changes on 6
      existing `403` responses; confirmed via `git diff`, no path/schema removed).
    - **Tests:** no new test files — `rbac.UserAdminGateTest`'s existing forced-change end-to-end
      case extended to assert `GET /me`'s `mustChangePassword` flips true→false around the change
      call (previously it only asserted `/me` was blocked, which is no longer the behavior);
      `rbac.PasswordChangeEnforcementFilterExemptionTest` extended identically, and its "an ordinary
      endpoint is blocked" example moved from `/me` (now exempt) to `/api/v1/users`.
    - **DONE & MERGED via PR #46** (2026-07-29, merge commit `9c5c34f`, merged on Majd's explicit
      instruction via admin override — no green CI run, same GitHub Actions billing block as PR #41/
      #43/#45; `mvn verify` 375/375 run in the prior session was the substitute gate). The fix is now
      on `main`; see "Last completed" below.
- **KH-2.2b-BE — tenant user management + onboarding completion (D5+D6+D8)** (session
  `feat/KH-2.2b-BE-tenant-users`, 2026-07-28, spec `docs/specs/FS-2.2-rbac-granularity.md` §3):
  the tenant-staff user-management surface (`GET/POST /api/v1/users`, roles/lock/unlock/disable/
  reset-password, `tenant:admin`-gated, console-session-only), onboarding completion (`initialAdmin`
  on tenant create + `POST /admin/tenants/{id}/users`), the forced-password-change gate, and the
  race-proofed last-tenant-admin guard. `mvn verify` green, **375/375 tests (31 new)**. New
  `user.*` message keys in both bundles, **Arabic-speaker review confirmed by Majd before merge,
  no wording changes needed** — same pattern as every prior session's new-key set. **DONE &
  MERGED via PR #45** (2026-07-28, gitleaks scanned locally, clean, merged without waiting on CI
  per the same GitHub Actions billing block as PR #41 — see "CI status (temporary)" at the top of
  this file).
    - **Verify-against-code findings (recorded before writing, per the brief):** `app_user` (V1
      baseline) had no `must_change_password` column and no `updated_at`/`@Version` — added the flag
      via new migration, confirmed argon2id password hashing end-to-end
      (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`, `AuthService#login`/
      `AdminBootstrap#bootstrapIfNeeded`), and confirmed a user's roles are loaded into the session
      principal as the **union of their roles' scopes** (`RoleRepository#findScopesByUserId` →
      `LoginResult.scopes` → `KhatmAuthenticationToken`'s `SCOPE_*` authorities) — no role codes and
      no live per-request re-read, which is exactly why the last-admin guard reasons about the
      `tenant:admin` **scope** (via a native `role.scopes` join query), not the `TENANT_ADMIN` role
      code, and why the forced-change flag needed its own live-read filter rather than living in the
      principal. The plaintext-once pattern (`ApiKeyService`'s `CreatedApiKey` domain record +
      `CreateApiKeyResponse` web record) was confirmed and mirrored verbatim as `CreatedUser`/
      `CreateUserResponse` for temporary passwords.
    - **A genuine architectural fork, found and resolved before writing (per the brief's own
      self-stop trigger):** `POST /admin/tenants` with `initialAdmin` must create an `app_user` +
      seed `rbac`'s role catalog — but `rbac` already declares `allowedDependencies` including
      `tenant :: api` (for `TenantDirectory`), so `tenant → rbac` would be a Modulith cycle
      (`ModulithBoundariesTest` verifies acyclicity). Presented as an explicit architect decision
      rather than guessed: **approved resolution** — the onboarding *create* endpoint relocates to a
      new `rbac.web.TenantProvisioningController`, mirroring the exact precedent
      `rbac.web.ConsumingPartyKeyController` already set for the identical class of problem
      (KH-1.4.4, a cross-module endpoint that must live in the module owning the extra tables it
      touches). `tenant.web.TenantAdminController` keeps list/get/suspend/activate; the URL
      (`POST /api/v1/admin/tenants`), its `platform:admin` gate, and `TenantAdmin#create`'s own
      resumable-onboarding semantics are all unchanged — only the handling bean moved modules. New
      `rbac.domain.TenantProvisioningService` orchestrates both halves (calls `tenant :: api` for the
      tenant+key+status-list, then `RoleCatalogSeeder`/`UserAdminService` for the rbac-side half),
      wrapped in `shared.OnBehalfOfExecutor#runAsTenant` for the genuinely cross-tenant steps — the
      first *real* exercise of that D4 mechanism (KH-2.2a wired it but never actually needed the
      cross-tenant switch on a request that also does other RLS-protected writes). Recorded here as a
      reinforced convention: **cross-module orchestration endpoints live in the module that owns the
      extra tables**, not the module that conceptually "owns" the feature.
    - **A second, real bug found only by running the new orchestration (caught by its own new
      tests, not by inspection):** `OnBehalfOfExecutor#runAsTenant`'s `finally` block clears
      `TenantContext`'s `ThreadLocal` entirely rather than restoring whatever was ambient before it
      ran — correct for its original, single-step call site (`AuthController#createApiKey`), but
      `TenantProvisioningService#onboard` calls `TenantAdmin#create` (which also sets-then-clears
      `TenantContext` internally) *before* calling `runAsTenant`, wiping the calling platform admin's
      own ambient tenant that `runAsTenant` needs alive to write its pre-switch `ON_BEHALF_OF` audit
      row. Fixed by capturing the caller's tenant id/slug before `create()` runs and re-`set`ting it
      immediately after, before `runAsTenant` is invoked. Confirmed via the live compose e2e (DoD) —
      surfaced originally as a 500 in `TenantAdminGateTest`/`CrossTenantIsolationTest`/
      `SuspendedTenantAuthTest`, all of which route through `POST /api/v1/admin/tenants`.
    - **Resumable onboarding, extended exactly where the brief asked, scoped narrowly:**
      `TenantAdmin#create`'s own `KH-TNT-0409` fires once tenant+key are both done — this session's
      orchestration introduces a *later* crash window (tenant+key done, catalog/admin not yet
      provisioned). `TenantProvisioningService#onboard` catches that specific conflict **only when
      `initialAdminUsername` is non-null** (there is new rbac-side work to resume only in that case),
      resolves the already-onboarded tenant by slug, and continues into the catalog/admin resume —
      preserving the pre-existing KH-2.1 contract that a plain re-create with no `initialAdmin`
      against an already-onboarded slug still conflicts (a real regression caught by the existing,
      unrelated `TenantAdminGateTest.create_duplicateSlug_returns409_andLeavesOneRow`, which pinned
      exactly this boundary). Verified live: retrying the same onboard call with the same
      `initialAdminUsername` resumes (200, `temporaryPassword: null` since the admin already exists,
      exactly one `app_user` row); retrying with no `initialAdmin` still 409s.
    - **Per-tenant role catalog — a real gap, not originally scoped, found during verification:**
      `V1__baseline.sql` seeded the three catalog roles (`PLATFORM_ADMIN`/`TENANT_ADMIN`/
      `ISSUER_OPERATOR`) only for the default tenant; `V10`'s `WHERE code = ...` rescoping matched
      only those same rows, since no other tenant had any role rows at all. Every tenant onboarded
      before this session (from KH-2.1's own e2e tenants onward) has **zero** role rows — would have
      broken both D5 (assign from catalog) and D6 (first `TENANT_ADMIN`) silently. Fixed two ways,
      per the approved plan: (a) new `rbac.domain.RoleCatalogSeeder#ensureCatalog` (idempotent,
      find-or-create per role) called from the onboarding orchestration for every newly/resumed
      onboarded tenant; (b) new `V12__seed_tenant_role_catalogs.sql`, a data-only, idempotent
      (`WHERE NOT EXISTS`) backfill for every tenant that already existed, seeding the identical V1 +
      V10 granular-scope values — confirmed via `db.SeededRoleScopesTest`-style assertions that no
      role anywhere ever carries the retired `admin` scope.
    - **V12's own mechanism, verified against the code, not assumed:** confirmed Flyway runs on a
      *separate owner/superuser* datasource (`SPRING_FLYWAY_USER`/`spring.flyway.user`, distinct from
      the locked-down `khatm_app` runtime role, `docker-compose.yml`/`support.IntegrationTestSupport`)
      — the same mechanism `V9__resign_status_lists.sql` and `V10` already relied on to mutate every
      row of a `FORCE ROW LEVEL SECURITY`-protected table with plain DML and no `app.tenant_id`
      needed. The precedent transferred cleanly; no stop-and-report was needed.
    - **D5 — tenant user management, `rbac.web.UserAdminController`, `/api/v1/users/**`:** `GET`
      (list, newest-first) / `POST` (create — username slug validated, roles from the fixed catalog,
      temp password plaintext-once, `must_change_password` set) / `POST /{id}/roles` (replace,
      delete-all-then-reinsert) / `/{id}/lock` / `/{id}/unlock` (no last-admin guard — can only add an
      active admin) / `/{id}/disable` / `/{id}/reset-password` (new temp password, forces change).
      Gated `ScopeGuard.requireScopeAndUserSession(TENANT_ADMIN)` — console session only, no API key
      of any kind (same "operator tool, not an integration" judgment call credential search/stats
      already made). `V11__user_password_change_and_role_grants.sql` also grants `DELETE` on
      `user_role` to `khatm_app` (role-set replacement needs it) — the same documented,
      table-scoped exception `V7` already made for `consuming_party_schema`.
    - **Last-tenant-admin guard, race-proofed (D5/D8):** `UserAdminService` takes a per-tenant
      Postgres `pg_advisory_xact_lock` (keyed on `hashtext(tenantId)`) before any guarded mutation
      (lock/disable/role-change), serializing concurrent operations within a tenant so the
      count-then-act guard (`AppUserRepository#countActiveAdminsExcluding`, a native join counting
      `ACTIVE` users holding the `tenant:admin` scope via any role) is never raced. New
      `db.ConcurrentLastAdminTest` (joins the `ConcurrentConsumeTest` race-test family, per the
      brief): two concurrent locks against a tenant's final two admins → exactly one succeeds, one
      409s, tenant retains exactly one active admin.
    - **Forced password-change gate (D5), live per-request:** new
      `rbac.security.PasswordChangeEnforcementFilter`, wired into the session chain only (API keys
      carry no human password) immediately after `TenantContextFilter` (needs the tenant context
      resolved first, both for the RLS-scoped read and to target the right tenant) — reads
      `app_user.must_change_password` **fresh on every request**, never cached in the session
      principal, since an admin's `reset-password` call must bite on the target's very next request
      even mid-session. Every call except `POST /api/v1/users/me/password` (+ logout + the existing
      public paths) is rejected with the new, distinct `403 KH-USR-0403` so the console can route to
      a change screen rather than a generic missing-scope 403. New
      `rbac.PasswordChangeEnforcementFilterExemptionTest` pins the exemption list exactly; extended
      `TenantContextFilterCoverageTest` proves filter ordering structurally (session chain only,
      always after `TenantContextFilter`).
    - **D8 — errors/audit:** new `KH-USR-0400/0403/0404/0409/0423` (a new `USR` module tag —
      second, after `CLM`, to name a bounded concern rather than a 1:1 Java module; documented in
      `ErrorCode`'s own class Javadoc). `KH-USR-0423` is the **first code whose suffix diverges from
      its HTTP status** — `0423` (mnemonic for HTTP 423 Locked, thematically exact for "locks the
      tenant out of its own administration") but wire status `409 Conflict` per the approved brief's
      exact wording; documented as a deliberate, first-time exception in both the enum Javadoc and
      `docs/error-codes.md`. New `AuditAction.USER_ROLES_CHANGED/LOCKED/UNLOCKED/DISABLED/
    PASSWORD_RESET/PASSWORD_CHANGED` (`USER_CREATED` already existed, reused, not duplicated). Five
      new `user.*` keys in both bundles, same commit — **Arabic-review gate applies**.
    - **Contract:** `docs/api/openapi.json` regenerated via `OpenApiContractTest`'s own mechanism —
      additive-only (8 new paths: `/api/v1/users` + its 6 action sub-paths +
      `/api/v1/admin/tenants/{id}/users`; `CreateTenantRequest` schema replaced by
      `OnboardTenantRequest`/`OnboardTenantResponse`/`InitialAdminRequest`/`InitialAdminResponse`/
      `CreateUserRequest`/`CreateUserResponse`/`ChangePasswordRequest`/`DisplayNameI18nRequest` —
      confirmed via path-set diff, no path removed). `docs/error-codes.md` regenerated (5 new
      `KH-USR-*` rows).
    - **Tests (31 new):** `db.ConcurrentLastAdminTest` (1, the mandatory race test), `db
    .TenantRoleCatalogTest` (4 — V12 backfill-statement idempotency proven directly rather than
      asserted over the shared suite's incidental fixture data, since several pre-existing test
      classes deliberately create tenants via `TenantAdmin#create` service-level with no rbac
      orchestration involved; `RoleCatalogSeeder` exact-scope-set + idempotency), `rbac
    .UserAdminGateTest` (10 — scope gate, full lifecycle with audit rows, duplicate-username 409,
      unknown-role 400, sole-admin disable/role-change 409, second-admin disable succeeds, the
      forced-change gate end-to-end over real HTTP), `rbac.domain.UserAdminServiceTest` (9 — service
      level), `rbac.domain.TenantProvisioningServiceTest` (4 — onboard-with-admin, onboard-without,
      resume-fills-missing-never-duplicates, cross-tenant user create + `ON_BEHALF_OF` audit), `rbac
    .PasswordChangeEnforcementFilterExemptionTest` (1), plus 2 new cases in the existing
      `TenantContextFilterCoverageTest` and the extended `OnBehalfOfCallerAllowlistTest`/
      `OpenApiContractTest`/`ErrorCodesDocGenerationTest`/`SeededRoleScopesTest`-style assertions
      (no new test files for these, existing suites extended).
    - **DoD:** `mvn verify` green (375/375, up from 344). Live compose e2e against the rebuilt image
      (existing dev volume, V11/V12 applied cleanly, confirmed via `docker logs`) — onboard tenant
      WITH `initialAdmin` in one call (temp password shown once) → **documented pre-existing gap,
      not introduced this session**: bare `POST /api/v1/auth/login` cannot resolve a non-default
      tenant's user at all (`AuthService#login` reads the anonymous request's `TenantContext.current()`,
      which always falls back to the default tenant — `SuspendedTenantAuthTest`'s own Javadoc already
      documents this as out-of-scope multi-tenant console-login support), so the login-dependent DoD
      steps were run against a second user created under the **default** tenant instead (same
      mechanics, same code paths) — forced-password-change login → blocked on `/api/v1/auth/me` with
      `KH-USR-0403` → self-service change → flag cleared, normal access resumes on the same session →
      operator issues fine, 403 on schema writes and on `/api/v1/users` → disabling the sole
      `TENANT_ADMIN` → 409 `KH-USR-0423` → platform-admin adds a user to the OTHER (non-default)
      tenant via `/admin/tenants/{id}/users` → `ON_BEHALF_OF` audit row confirmed in `audit_log` →
      retried onboarding of the same slug+`initialAdmin` resumes idempotently (200,
      `temporaryPassword: null`, exactly one `app_user` row) → retried onboarding of the same slug
      with no `initialAdmin` still 409s (pre-existing contract preserved). **Arabic-speaker review
      gate for the five new `user.*` keys: not yet confirmed by Majd — merge blocker, PR not yet
      opened.**
- **KH-2.2a-BE — RBAC scope registry (D1–D4)** (session `feat/KH-2.2a-BE-scope-registry`,
  2026-07-28, spec `docs/specs/FS-2.2-rbac-granularity.md`): replaces the KH-0.6b coarse `admin`
  scope stand-in with a nine-scope deny-by-default registry (`issue, verify, consume, revoke,
  schema:manage, consumer:manage, key:manage, tenant:admin, platform:admin`) and re-gates every
  `/api/v1/admin/**` endpoint per its own family. `mvn verify` green, **344/344 tests (17 new)**.
  No new `ErrorCode`/message key (every 403 reuses `KH-RBC-0403`/`error.rbc.forbidden`), so no
  Arabic-review gate. **DONE & MERGED via PR #43** (2026-07-28, merge commit `238c54d`, merged on
  Majd's explicit instruction **without waiting on CI** — see "CI status (temporary)" below for
  why; branch `feat/KH-2.2a-BE-scope-registry` not deleted).
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
  addressed via a small cross-repo doc PR, see below). `mvn verify` green, **329/329 tests (9
  new)**. No new `ErrorCode`/message key (invalid `status` values reuse the existing
  `KH-SYS-0400/validation.failed`), so no Arabic-review gate. **DONE & MERGED via PR #41**
  (2026-07-28, merge commit `1c5a8ff`, fast-forward); branch
  `chore/credential-search-status-filter` deleted.
    - **Merged without a green CI run — GitHub Actions billing block, not a code issue, Majd's
      explicit instruction:** every check on PR #41 (`Build and verify`, `Trivy vuln scan`,
      `gitleaks`, `compose-smoke`) failed within ~10s with "The job was not started because recent
      account payments have failed or your spending limit needs to be increased" — an account-level
      GitHub Actions billing problem, confirmed by re-running the workflow (same result) and by the
      identical failure recurring on the post-merge push-triggered run against `main` itself
      (`gh run list --branch main`, run `30344326075`, still 13s/billing-blocked after the merge).
      Substitute verification actually performed before merging: local `mvn verify` green (329/329,
      logged pre-merge in this same entry), `docs/api/openapi.json`/`docs/error-codes.md`/message
      bundles confirmed additive-only/unchanged via their own tests, and **two** local unredacted
      `docker run zricethezav/gitleaks:latest detect --redact=0` scans (once before opening the PR,
      once again on the final pushed commit) both came back clean — the same standard PR #41's own
      CI job would have applied, just run manually. **Billing is still unresolved as of this
      merge** — the next session (or Majd) should check GitHub's Billing & plans settings before
      trusting any CI status badge on this repo at face value; a real code-breaking regression could
      currently merge with the exact same "checks failed" signature as this billing block.
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
      closes — **`khatm-console` PR #18 opened; updated post-merge to say #41 is now merged** and
      their `npm run contract:update` can proceed. `khatm-console` PR #18 itself is a docs-only
      change on that repo and is theirs to merge, not this session's to force.
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

## Last completed (moved from "Last completed")
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
