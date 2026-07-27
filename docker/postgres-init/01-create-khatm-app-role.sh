#!/bin/bash
# Provisions the `khatm_app` runtime DB role (spec FS-2.1 D3) before Flyway's first boot.
#
# Postgres's official image runs every script under /docker-entrypoint-initdb.d/ exactly once,
# only when the data directory is freshly initialized (an EMPTY volume) — an existing
# `khatm_pgdata` volume from before KH-2.1 will NOT pick this up automatically; see
# docs/deploy-staging.md and the README for the one-time manual step an existing environment
# needs. `khatm_app` itself has no BYPASSRLS, is not a table owner, and is granted only
# SELECT/INSERT/UPDATE (+ DELETE on the one documented exception, consuming_party_schema) by
# V7__rls_policies.sql's own GRANT statements — this script only creates the role, nothing else.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'khatm_app') THEN
      CREATE ROLE khatm_app LOGIN PASSWORD '${KHATM_APP_DB_PASSWORD:-khatm_app_local_only}'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
  END
  \$\$;

  GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO khatm_app;
  GRANT USAGE ON SCHEMA public TO khatm_app;
EOSQL
