-- KH-1.4.4-BE: consuming-party admin plane.
--
-- A consuming_party row's id is derived deterministically from (tenant_id, code) —
-- UUID.nameUUIDFromBytes("<tenant>:<code>") — so the same code always resolves to the same row
-- (ConsumingPartyRegistry#ensure). The code itself was never persisted: KH-0.2.1 only ever needed
-- the id, and V2__auth_api_keys.sql dropped the old api_key_hash stand-in. The admin plane
-- (KH-1.4.4) lists parties by code, creates them by code, and must guarantee that explicit admin
-- creation and implicit #ensure can never diverge into two rows for one code — all of which need
-- the code stored and uniquely constrained per tenant.
ALTER TABLE consuming_party ADD COLUMN code text;

-- Backfill any pre-existing rows (implicitly created by #ensure before this migration) with a
-- deterministic, collision-free placeholder. Their original caller-supplied code cannot be
-- recovered from the one-way id hash; a real deployment has none of these yet (platform v1 pilot),
-- and local/dev demo rows are recreated from scratch on every `docker compose down -v`.
UPDATE consuming_party SET code = 'legacy-' || id::text WHERE code IS NULL;

ALTER TABLE consuming_party ALTER COLUMN code SET NOT NULL;
ALTER TABLE consuming_party
  ADD CONSTRAINT consuming_party_tenant_code_key UNIQUE (tenant_id, code);
