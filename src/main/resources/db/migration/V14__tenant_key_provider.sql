-- V14__tenant_key_provider.sql — KH-2.3b (spec FS-2.3 D5/D6, veto V3).
--
-- V3's resolution: a per-tenant KMS-provider column (not a single platform-wide config) — a mixed
-- SaaS/on-prem deploy_mode needs different tenants on different backends at once, and the column
-- paves the way for per-party HSM in Phase 3 with no further schema migration.
--
-- Two changes:
--   1. issuer_key.provider's CHECK only allowed ('SOFT','KMS','PKCS11') — V1 anticipated a generic
--      'KMS' category but this session gives Vault Transit its own concrete value ('VAULT') rather
--      than overloading 'KMS', since a later AWS/GCP KMS adapter (spec D5, "same SPI, out of scope
--      today") would otherwise be indistinguishable from Vault when KeyLifecycleService routes a
--      sign/publicKey call to the right KeyProvider bean by this column's value.
--   2. tenant.key_provider — the tenant's current signing-key provider, defaulting to 'SOFT' (every
--      tenant is born on SOFT; migrating onto Vault is an explicit rotation, spec D6 — never a
--      config a tenant is created with). Kept in sync by key.domain.KeyLifecycleService#rotate via
--      the existing KeyRotated event (extended with a provider field) — never written directly by
--      the key module, which must not depend on tenant :: api (see key/package-info.java).

ALTER TABLE issuer_key DROP CONSTRAINT issuer_key_provider_check;
ALTER TABLE issuer_key ADD CONSTRAINT issuer_key_provider_check
  CHECK (provider IN ('SOFT', 'VAULT', 'KMS', 'PKCS11'));

ALTER TABLE tenant ADD COLUMN key_provider text NOT NULL DEFAULT 'SOFT'
  CHECK (key_provider IN ('SOFT', 'VAULT', 'KMS', 'PKCS11'));
