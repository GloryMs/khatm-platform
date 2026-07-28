# tenant

Multi-tenancy management (KH-2.1, spec FS-2.1). Every business table across the platform carries a
`tenant_id` column pointing here.

**Responsibilities:** tenant onboarding (`TenantAdminService#create` — tenant row + first `ACTIVE`
signing key + default status list, all before the call returns), suspend/activate, and read-only
resolution by id/slug (`TenantDirectoryService`).

**Exposed API** (`tenant.api`):
- `TenantDirectory` — read-only lookup by id/slug. `rbac` depends on this (`rbac.security
  .TenantContextFilter`, `ApiKeyService#verify`, `AuthService#login`) to resolve a principal's
  tenant and enforce suspension.
- `TenantAdmin` — the admin plane behind `/api/v1/admin/tenants` (`platform:admin` scope
  exclusively, spec FS-2.2 D2 — the entire path has no other caller, so `TenantAdminService#create`
  keeps its own manual `TenantContext` switch rather than going through `shared.OnBehalfOfExecutor`,
  which exists for endpoints shared with a lesser-privileged self-service caller; see that class's
  Javadoc).

**Cross-module dependencies (one-way, deliberately):** `key :: api` (`TenantKeyProvisioner`) and
`status :: api` (`StatusListAllocator#ensureList`) for onboarding. Neither `key` nor `status`
depends back on `tenant :: api` — that would be a Modulith cycle — so the two public, slug-keyed
HTTP endpoints that need to resolve an arbitrary tenant by slug live here instead of in their
"natural" module:
- `TenantJwksController` — `GET /t/{tenantSlug}/.well-known/jwks.json` (spec D8). The legacy
  `GET /.well-known/jwks.json` stays in `key.web`, aliasing the default tenant only.
- `TenantStatusListController` — `GET /sl/{tenantSlug}/{listCode}`, relocated from `status.web`
  (spec D8) now that it needs to resolve any tenant, not just the default one.

**Onboarding is resumable, not strictly atomic (spec V3):** `SoftKeyProvider` writes its keystore
file synchronously, outside any Postgres transaction, so true atomicity across (tenant row + key +
status list) isn't achievable. Rather than a compensating delete (business tables are never
deleted) or a partial-failure error code, calling `create` again with a slug whose onboarding
previously died partway through resumes it; only a slug that already has a fully-onboarded tenant
(an `ACTIVE` key present) is a genuine `KH-TNT-0409` conflict.

**Events in:** none. **Events out:** none yet.

**Tables owned:** `tenant`.

**Status:** KH-2.1 Part A (this doc) — tenant context resolution, admin/onboarding plane, per-tenant
trust endpoints. Part B adds RLS enforcement on top of this same module's persistence layer.
