-- V17__seed_org_admin_role.sql — KH-2.6b-BE (spec FS-2.5 §3), data-only.
--
-- RoleCatalog.java's fixed catalog grows from three roles to four: ORG_ADMIN, carrying only the
-- new org:admin scope. RoleCatalogSeeder#ensureCatalog (find-or-create per role code) already
-- picks up new tenants going forward, but — same gap V12__seed_tenant_role_catalogs.sql closed
-- for the original three roles — every tenant onboarded before this migration has zero ORG_ADMIN
-- rows. This backfills them.
--
-- Idempotent (insert-where-absent per (tenant_id, code)); re-running is a no-op. Runs as Flyway's
-- owner/superuser role (bypasses FORCE ROW LEVEL SECURITY), the identical mechanism V12 used.

INSERT INTO role (id, tenant_id, code, name_i18n, scopes)
SELECT gen_random_uuid(), t.id, 'ORG_ADMIN',
  '{"en":"Organization Administrator","ar":"مدير الجهة الأم"}'::jsonb,
  ARRAY['org:admin']::text[]
FROM tenant t
WHERE NOT EXISTS (
  SELECT 1 FROM role r WHERE r.tenant_id = t.id AND r.code = 'ORG_ADMIN'
);
