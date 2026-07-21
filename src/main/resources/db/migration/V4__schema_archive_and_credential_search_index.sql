-- KH-1.1-BE: schema management + credential search.
--
-- Part A (schema authoring, KH-1.1.1): PUBLISHED schemas can now be archived (stops NEW issuance;
-- existing credentials/verification unaffected) — a lifecycle step V1's CHECK constraint didn't
-- anticipate. Additive widening only; no existing row is ever ARCHIVED, so no data migration is
-- needed. The constraint name below is Postgres's own default for an unnamed column-level CHECK
-- (confirmed against a scratch table before writing this migration, not guessed).
ALTER TABLE credential_schema DROP CONSTRAINT credential_schema_status_check;
ALTER TABLE credential_schema ADD CONSTRAINT credential_schema_status_check
  CHECK (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED', 'ARCHIVED'));

-- Part B (credential search, KH-1.1.4): GET /api/v1/credentials sorts by issuedAt (created_at)
-- DESC unconditionally. V1's indexes already cover the schemaId filter (credential_tenant_schema)
-- and the pseudoRef-resolved holderId filter (credential_holder), and ref is already globally
-- unique/indexed — but the base tenant-scoped, sorted scan every call performs (with or without
-- those filters layered on top) had no index of its own to drive the pagination.
CREATE INDEX credential_tenant_created ON credential (tenant_id, created_at DESC);
