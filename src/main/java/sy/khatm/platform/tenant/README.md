# tenant

Multi-tenancy management. Every business table across the platform carries a `tenant_id`
column pointing here, in preparation for KH-2.1 (Postgres RLS, per-request tenant resolution).

**Events in:** none. **Events out:** none yet.

**Tables owned:** `tenant`.

**Status:** KH-0.2.1 adds the `Tenant` entity + repository only so `ddl-auto: validate` covers
the table. The single-tenant MVP does not look up rows here — it uses the fixed constant
`sy.khatm.platform.shared.TenantContext.DEFAULT_TENANT_ID`, seeded by `V1__baseline.sql`.
Tenant lifecycle (create/suspend), quotas, and feature flags are KH-2.x.
