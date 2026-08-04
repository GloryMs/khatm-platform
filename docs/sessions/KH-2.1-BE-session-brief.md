# Session Brief — KH-2.1-BE: Multi-Tenancy Core (single merged session)

> **Repo:** khatm-platform · **Branch:** `feat/KH-2.1-BE-multi-tenancy-core`
> **Spec:** FS-2.1 (APPROVED 2026-07-26, veto points V1–V4 resolved to their proposed defaults — see §0 below). This brief derives from the spec; **the code is the authority over both** wherever they conflict with reality — record any divergence in `docs/STATE.md`.
> **Model:** Sonnet only (security-critical: RLS, key onboarding, auth path).
> **Session shape (Majd's explicit instruction):** Parts A and B run in ONE session, separated by a hard checkpoint (§4). Do not interleave them.

---

## 0. Resolved veto points (do not re-open)

- **V1 — YES**: dedicated runtime DB role `khatm_app` (no BYPASSRLS, not table owner) + separate Flyway datasource on the owner role (`spring.flyway.user`/`password`). Compose gains a postgres init script creating `khatm_app` before first boot.
- **V2**: legacy `/.well-known/jwks.json` stays as an alias for the default tenant through all of Phase 2 (deprecated in OpenAPI, never removed this phase).
- **V3**: tenant onboarding atomicity is the implementer's pick (atomic if `KeyProvider.rotate` allows; otherwise documented compensation pattern). Record which path was taken and why in the PR body.
- **V4**: suspending a tenant blocks **issuance only**. Verify, consume, `/sl/**`, and JWKS keep working for already-issued credentials.

## 1. Session preamble (protocol)

1. Confirm `main` includes the KH-1.1.5-BE merge via `git log` directly (hygiene declared done by Majd 2026-07-26 — verify, don't assume). If absent: **stop and report**.
2. Docker Desktop up (`docker info` polls); Testcontainers reachable.
3. Read `docs/CONVENTIONS.md` §7 and the consuming-party admin plane (`consumer` module, KH-1.4.4-BE) — Part A mirrors its shape deliberately.

## 2. Verify-against-the-code duties (before writing anything)

- **Event payloads**: confirm whether `StatusListChanged` (and any other `@Externalized` event a worker consumes) already carries `tenantId`. If not, adding it is in scope for Part B (workers must restore tenant context from the event, FS-2.1 D5).
- **Table list for V7**: enumerate business tables from the live schema (V1–V6 applied), not from FS-0.2 — columns/tables added after that spec (V5 `code`, V6 index) exist. `tenant` itself is excluded from RLS.
- **Principal columns**: confirm `app_user.tenant_id` and `api_key.tenant_id` exist and are populated by current auth paths.
- Business-path DELETEs: grep for any repository `delete`/`DELETE` on business tables before writing the `khatm_app` GRANTs (expected: none per FS-0.2 D6; the `disclosures_enc` zeroing is an UPDATE).

## 3. Part A — tenant context + admin plane + per-tenant trust endpoints (FS-2.1 D1, D6, D7, D8, D9)

**No RLS in this part.** Everything runs on the existing service-layer discipline.

1. **D1 — Tenant context**: `TenantContextFilter` (after auth filters) resolving tenant strictly from the authenticated principal (`app_user.tenant_id` / `api_key.tenant_id`); request-scoped `TenantContext`. Replace every runtime call site of `TenantContext.DEFAULT_TENANT_ID` with the resolved context; the constant remains legal only in seeders + `local`-profile config + tests. Add a grep-gate test enforcing that package allowlist (same family as the directional-widgets / public-path list tests).
2. **D6 — Admin plane** (mirror the consuming-party plane): `POST /api/v1/admin/tenants` (create = full onboarding: tenant row → first ACTIVE key via `KeyProvider.rotate` → default status list `<slug>-<year>`, capacity 131072), `GET` list (newest first) + `GET /{id}`, `POST /{id}/suspend`, `POST /{id}/activate`. Existing `admin` scope; every new endpoint declares scope explicitly + public-path list test updated.
3. **D7 — SUSPENDED bites in auth**: principal of a SUSPENDED tenant → same 401/403 failure paths as the suspended consuming-party pattern (KH-1.4.4 D4). Public paths for that tenant's issued credentials stay alive.
4. **D8 — Per-tenant JWKS**: public `GET /t/{tenantSlug}/.well-known/jwks.json`; legacy path aliases the default tenant, marked deprecated in OpenAPI. `PublicUrlBuilder` gains slug-aware builders; newly issued credentials reference the new path.
5. **D9 — Errors/audit**: `KH-TNT-0400/0404/0409` (+ `0422` only if the compensation path is taken), slug regex = the consuming-party `code` regex. `AuditAction.TENANT_{CREATED,SUSPENDED,ACTIVATED}` (entityRef = slug), all writes via `AuditService#record`. New `tenant.*` keys in BOTH bundles, same commit; `MessageBundleParityTest` green.
6. Regenerate `docs/api/openapi.json` + `docs/error-codes.md` via their own tests, never hand-edited. Contract diff must be additive-only.

### §4. HARD CHECKPOINT (do not cross until all true)

- `mvn verify` green, all suites.
- Contract diff reviewed: additive-only.
- **Commit Part A as its own commit** (message prefixed `KH-2.1-BE Part A:`). Part B starts only after this commit exists. If Part A cannot reach green, **stop the session and report** — do not start RLS on a broken base.

## 5. Part B — RLS enforcement + leak suite (FS-2.1 D2, D3, D4, D5, D10)

1. **`V7__rls_policies.sql`**: per business table — `ENABLE` + `FORCE ROW LEVEL SECURITY`, policy `tenant_isolation USING (tenant_id = current_setting('app.tenant_id', true)::uuid)`, policy `system_access USING (current_setting('app.khatm_system', true) = 'on')`; `GRANT SELECT, INSERT, UPDATE` (+ sequence USAGE if any) to `khatm_app`. No DELETE grants unless §2's grep proved a documented exception. V1–V6 untouched; `MigrationImmutabilityTest`/`MigrationCleanBootTest` green; checksum appended to `db/migration-checksums.lock`.
2. **Role provisioning (V1 default)**: compose postgres init script creates `khatm_app` (idempotent); `khatm-api`/`khatm-worker` datasource switches to `khatm_app`; Flyway on the owner role via `spring.flyway.user`/`password`. Document the staging equivalent in `docs/deploy-staging.md`. Testcontainers base classes must provision the role the same way before migrations run.
3. **D4 — Context propagation**: `set_config('app.tenant_id', :id, true)` at transaction begin (mechanism = implementer's pick). **HARD CONSTRAINT: transaction-scoped only — `SET SESSION` is forbidden** (Hikari pool leakage). Cover with a test proving the variable does not survive the transaction on a reused connection.
4. **D5 — `SystemAccessExecutor`** in `shared`: single wrapper setting `app.khatm_system = 'on'` (also transaction-scoped), callable only by the enumerated services: redeem lookup, verify lookup, status-list read, JWKS read, Redis Streams workers. Test asserting the caller list matches the enumeration exactly. Workers restore tenant context from event payloads (per §2 finding).
5. **D10 — `CrossTenantIsolationTest`** (joins the mandatory named tests): seed tenants A/B with full entity sets; (a) HTTP layer: A's principal vs every enumerated B resource → 403/404, zero rows; (b) defense-in-depth: raw repository query as `khatm_app` under A's context, with B rows present, no service-layer filter → only A rows; (c) missing context → zero rows (closed-fail).
6. Update `shared/README.md`, `tenant`-related module README + `package-info.java`, `docs/CONVENTIONS.md` if a new durable convention emerged.

## 6. Definition of Done

1. `mvn verify` green, full suite (report count: N total / new).
2. **Live compose e2e** against the rebuilt stack, run for real: create two new tenants via the new plane → each gets an ACTIVE key + status list → issue a credential under each → per-tenant JWKS resolves per slug → tenant A's session cannot read tenant B's credential (404) → suspend tenant B → its operator issuance 401/403s, while verifying its already-issued credential still returns 200 `valid:true` and its `/sl/` + JWKS still serve (V4). Three isolated tenants total (default + 2) — **record this run in STATE as the first half of the Phase-2 exit evidence**.
3. Contract diff additive-only; legacy JWKS path still present.
4. PR opened, **not merged** — merge waits on Majd's review + **Arabic-review gate for all `tenant.*` keys (hard merge blocker)**.
5. `docs/STATE.md` updated end-of-session (including any spec-vs-code divergences found in §2).

## 7. Self-stop gates

- `main` missing KH-1.1.5-BE at preamble → stop.
- Part A not green → stop before Part B (§4).
- Any need to touch V1–V6 migrations → stop and report (never edit).
- Event payloads lack `tenantId` AND adding it would break the additive-only contract for any published external shape → stop and ask (internal `@Externalized` payloads are expected to be safely extendable; stop only if that expectation proves false).
- Anything requiring `SET SESSION` or a BYPASSRLS grant → stop; that path is forbidden by spec.
