-- Testcontainers-only: provisions the `khatm_app` runtime role (spec FS-2.1 D3) before Flyway
-- runs, since V7__rls_policies.sql's GRANT statements fail if the role doesn't exist yet. Applied
-- via PostgreSQLContainer#withInitScript on every container this codebase's tests start — mirrors
-- (with a fixed test-only password) the deploy-time role provisioning docker/postgres-init/ and
-- docs/deploy-staging.md document for real environments. Never used outside a Testcontainers
-- container's own throwaway instance.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'khatm_app') THEN
    CREATE ROLE khatm_app LOGIN PASSWORD 'khatm-app-test-only-password'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
  END IF;
END
$$;

GRANT CONNECT ON DATABASE test TO khatm_app;
GRANT USAGE ON SCHEMA public TO khatm_app;
