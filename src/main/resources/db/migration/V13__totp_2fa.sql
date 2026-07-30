-- V13__totp_2fa.sql — KH-2.2c (spec FS-2.2 V1): RFC 6238 TOTP second factor.
--
-- app_user gains three nullable columns for its one TOTP secret (1:1 with the user):
--   totp_secret_enc    — AES-256-GCM ciphertext (nonce-prepended), never plaintext at rest.
--   totp_enrolled_at   — set every time POST /users/me/totp/enroll (re)generates a secret; an
--                        enrollment that is never confirmed within khatm.auth.totp.enroll-ttl of
--                        this timestamp cannot be confirmed later (rbac.domain.TotpService checks
--                        this at confirm time — no separate expiry sweep needed).
--   totp_confirmed_at  — set on POST /users/me/totp/confirm; NULL means "no active TOTP" for both
--                        the mandatory-enrollment gate and the login-challenge check.
ALTER TABLE app_user
  ADD COLUMN totp_secret_enc bytea,
  ADD COLUMN totp_enrolled_at timestamptz,
  ADD COLUMN totp_confirmed_at timestamptz;

-- One-time recovery codes (10 minted on every successful confirm) — a separate table, not columns,
-- because each code is individually consumed exactly once and the set is regenerated on every
-- (re)confirm; a join table is the natural shape for that, the same reasoning V1 already applied to
-- user_role rather than an array column on app_user.
CREATE TABLE user_totp_recovery_code (
  id         uuid PRIMARY KEY,
  tenant_id  uuid NOT NULL REFERENCES tenant(id),
  user_id    uuid NOT NULL REFERENCES app_user(id),
  code_hash  text NOT NULL,
  used_at    timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX user_totp_recovery_code_user ON user_totp_recovery_code (user_id);

-- RLS, the same shape V7__rls_policies.sql established for every business table carrying tenant_id
-- (this table did not exist at V7 time, so it needs its own identical setup here).
ALTER TABLE user_totp_recovery_code ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_totp_recovery_code FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_totp_recovery_code USING
  (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY system_access ON user_totp_recovery_code USING
  (current_setting('app.khatm_system', true) = 'on');
GRANT SELECT, INSERT, UPDATE ON user_totp_recovery_code TO khatm_app;
-- No DELETE grant (V7's documented default): a reset/re-enrollment invalidates old codes by
-- UPDATE-ing used_at, never by deleting rows — consistent with audit_log's own append-only spirit
-- for this table's own append-only-except-the-used-flag shape.
