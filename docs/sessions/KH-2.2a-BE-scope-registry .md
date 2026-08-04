Session: feat/KH-2.2a-BE-scope-registry — spec FS-2.2 (APPROVED 2026-07-28, veto V1–V4 resolved
in the spec's §4; do not re-open). Branch off latest origin/main. Sonnet only (rbac module).

VERIFY-AGAINST-CODE FIRST (report before writing):
- Enumerate the LIVE endpoint surface and each endpoint's current scope gate (the code is the
  authority; the D2 mapping in the spec is a shape, not an inventory).
- How role.scopes is read into the session/principal (login path) and into API-key auth — V10
  rewrites data, so confirm nothing caches scopes beyond session lifetime.
- Confirm ISSUER_OPERATOR's read paths per veto V2: schema READ endpoints sit under action
  scopes (issue/verify/...), schema:manage gates writes only.

BUILD:
1. D1 canonical scope registry: issue, verify, consume, revoke, schema:manage, consumer:manage,
   key:manage, tenant:admin, platform:admin. Deny-by-default: an endpoint with no declared scope
   fails the path-list test. Legacy 'admin' removed from the registry entirely (veto V3: clean cut).
2. D2 re-gate every endpoint per the spec's mapping + your verified inventory. TENANT API-key
   management endpoints go under tenant:admin (veto V4); signing-key endpoints under key:manage;
   /api/v1/admin/tenants/** under platform:admin exclusively. Produce the full endpoint→scope
   table in the PR body.
3. D3 migration V10 (append-only, data-only): rewrite seeded roles' scopes —
   PLATFORM_ADMIN = all nine; TENANT_ADMIN = all except platform:admin;
   ISSUER_OPERATOR = issue, verify, revoke. 'admin' scrubbed from every role.
   V1–V9 untouched; immutability + clean-boot tests green; checksum lock updated.
   Deployment note in PR body: existing console sessions must re-login post-deploy.
4. D4 OnBehalfOfExecutor in shared (mirror SystemAccessExecutor exactly): platform:admin
   verified FIRST, then tenant context set to the explicit target for that transaction;
   enumerated-caller list + exact-match test; every use audited (AuditAction.ON_BEHALF_OF,
   entityRef = target tenant slug). Wire the existing /admin/tenants/{id}/... surface through it
   where it touches tenant-scoped data.
5. Scope-matrix test (inherits the KH-1.1.3 pattern): for every endpoint, its declared scope is
   accepted and every other scope is rejected — generated from the same source of truth the
   public-path-list test uses, so a new endpoint cannot dodge the matrix.
6. Contract regenerated via its test (OpenAPI security schemes reflect the new scopes) — this
   IS a breaking change to scope semantics by design (veto V3); flag it prominently in the PR
   body. No new user-facing message keys expected (no Arabic gate); if one becomes necessary,
   both bundles same commit + flag for Majd.

DoD: mvn verify green (report N/N); live compose e2e: login as seeded TENANT_ADMIN → schema
management works (schema:manage) → consuming-party admin works (consumer:manage) → /admin/tenants
rejected (403, lacks platform:admin) → login as PLATFORM_ADMIN → /admin/tenants works → CrossTenant
and scope-matrix suites green. PR opened NOT merged; STATE updated.
Self-stop: any endpoint whose correct scope is genuinely ambiguous after reading the code →
stop and list them for Majd's call rather than guessing.