-- V12__seed_tenant_role_catalogs.sql — KH-2.2b-BE (spec FS-2.2 D5/D6), data-only.
--
-- Every tenant needs the three fixed catalog roles (PLATFORM_ADMIN, TENANT_ADMIN,
-- ISSUER_OPERATOR) for user management to function: D5 assigns roles from this fixed catalog
-- ("custom roles out of scope"), and D6's initialAdmin is a TENANT_ADMIN. V1__baseline.sql seeded
-- them ONLY for the default tenant, and V10__scope_registry_rescope.sql rewrote their scopes with
-- the granular catalog using tenant-agnostic WHERE clauses — clauses that matched only the
-- default tenant's rows, since no other tenant had any roles at all. So every non-default tenant
-- that existed before this session has zero role rows.
--
-- This migration backfills the catalog for every tenant missing any of the three, with the exact
-- V1 display-name values and the exact V10 granular-scope sets — no tenant may ever hold a role
-- row carrying the scrubbed 'admin' scope (db.SeededRoleScopesTest pins that invariant; the values
-- below never include it).
--
-- Runs as Flyway's owner/superuser role (the separate spring.flyway.* datasource — see
-- docker-compose.yml / support.IntegrationTestSupport), which bypasses FORCE ROW LEVEL SECURITY:
-- the identical mechanism V9__resign_status_lists.sql and V10 used to touch every row of an
-- RLS-protected table with plain DML. app.tenant_id is not set here and need not be.
--
-- Idempotent (insert-where-absent per (tenant_id, code)); re-running is a no-op. New tenants
-- created AFTER this migration are seeded by the rbac onboarding orchestration at create time
-- (spec FS-2.2 D6), not by this migration — this backfill covers only tenants that already exist.

INSERT INTO role (id, tenant_id, code, name_i18n, scopes)
SELECT gen_random_uuid(), t.id, v.code, v.name_i18n, v.scopes
FROM tenant t
CROSS JOIN (VALUES
  ('PLATFORM_ADMIN',
     '{"en":"Platform Administrator","ar":"مدير المنصة"}'::jsonb,
     ARRAY['issue','verify','consume','revoke','schema:manage','consumer:manage','key:manage','tenant:admin','platform:admin']::text[]),
  ('TENANT_ADMIN',
     '{"en":"Tenant Administrator","ar":"مدير الجهة"}'::jsonb,
     ARRAY['issue','verify','consume','revoke','schema:manage','consumer:manage','key:manage','tenant:admin']::text[]),
  ('ISSUER_OPERATOR',
     '{"en":"Issuer Operator","ar":"مشغّل الإصدار"}'::jsonb,
     ARRAY['issue','verify','revoke']::text[])
) AS v(code, name_i18n, scopes)
WHERE NOT EXISTS (
  SELECT 1 FROM role r WHERE r.tenant_id = t.id AND r.code = v.code
);
