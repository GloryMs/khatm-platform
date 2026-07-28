-- KH-2.2a — Scope registry replacement (spec FS-2.2 D1/D3). Append-only, data-only, no schema
-- change (role.scopes was already text[]). Clean cut (spec V3): the legacy 'admin' scope is
-- scrubbed from every role, not kept as a superset alongside the new granular scopes — coexistence
-- would defeat deny-by-default and let a mapping mistake hide behind the old catch-all.
--
-- Deployment note: an existing console session's scopes are baked into its HttpSession at login
-- time (rbac.domain.AuthService#login) — this migration does not, and cannot, touch already-issued
-- sessions. Every console user must log out/in (or have their session expire) after this deploy to
-- pick up their new scope set. API keys are unaffected (rbac.domain.ApiKeyService#verify reads
-- api_key.scopes fresh on every request) and this migration does not touch the api_key table.

UPDATE role
SET scopes = ARRAY[
  'issue', 'verify', 'consume', 'revoke',
  'schema:manage', 'consumer:manage', 'key:manage', 'tenant:admin', 'platform:admin'
]
WHERE code = 'PLATFORM_ADMIN';

UPDATE role
SET scopes = ARRAY[
  'issue', 'verify', 'consume', 'revoke',
  'schema:manage', 'consumer:manage', 'key:manage', 'tenant:admin'
]
WHERE code = 'TENANT_ADMIN';

UPDATE role
SET scopes = ARRAY['issue', 'verify', 'revoke']
WHERE code = 'ISSUER_OPERATOR';
