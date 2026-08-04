Session: feat/KH-2.2c-BE-totp-2fa — closes FS-2.2 (veto V1: TOTP mandatory for sensitive scopes).
Branch off latest origin/main. Sonnet only (rbac/auth). RFC 6238 TOTP, standard otpauth:// URIs.

VERIFY FIRST: the login flow shape after KH-2.2d (slug + LoginResult + SessionAuthenticator);
the forced-password-change filter pattern (PasswordChangeEnforcementFilter) — forced 2FA
enrollment MUST reuse this exact pattern, not invent a sibling; the AES-GCM at-rest encryption
utility used for disclosures_enc (TOTP secrets reuse it); the plaintext-once response pattern
(recovery codes reuse it).

BUILD:
1. Enrollment (any authenticated user, self-service): POST /api/v1/users/me/totp/enroll ->
   secret (encrypted at rest) + otpauth:// URI returned once; POST /me/totp/confirm {code}
   activates (unconfirmed enrollments expire); on confirm -> 10 one-time recovery codes,
   hashed at rest, plaintext-once in the response.
2. Login challenge: password (+slug) valid AND user has active TOTP => login response signals
   totpRequired instead of a full session; POST /api/v1/auth/totp {code} completes it (accept
   ±1 time-step drift; rate-limit attempts via the existing lockout mechanics — verify how
   password lockout works and mirror it). Recovery path: POST /api/v1/auth/totp with a recovery
   code consumes it (audited, remaining count in details).
3. MANDATORY enforcement: users holding ANY of revoke, tenant:admin, platform:admin, key:manage
   (per FS-2.2 V1 + SEC §7) with no active TOTP => post-login they can ONLY reach the enroll/
   confirm endpoints (extend/parallel the PasswordChangeEnforcementFilter pattern; distinct
   error code so the console routes to enrollment). Live per-request read, same guarantee.
4. Admin reset: POST /api/v1/users/{id}/totp/reset (tenant:admin) + on-behalf-of variant
   POST /admin/tenants/{id}/users/{userId}/totp/reset (platform:admin, OnBehalfOfExecutor,
   caller list + test updated) — clears TOTP; user re-enrolls at next login if mandatory.
   Audited USER_TOTP_RESET.
5. New migration (V-next) for secret/recovery storage (implementer's shape: columns vs
   user_totp table — decide after reading app_user; report choice). V1–V12 untouched.
6. AuditAction.USER_TOTP_{ENROLLED,RESET} + auth audit on recovery-code use; KH-USR-/KH-RBC-
   codes as fits the existing registries; message keys EN/AR same commit — Arabic gate.
DoD: mvn verify green; live compose e2e: enroll+confirm (real authenticator app) -> logout ->
login => challenge -> wrong code rejected+lockout mirrors password policy -> right code in ->
recovery code works once -> platform_admin without TOTP is walled to enrollment -> admin reset
forces re-enrollment. PR opened NOT merged; STATE updated. Contract additive-only.
Self-stop: if signaling totpRequired cleanly conflicts with the existing LoginResult contract
shape -> stop and present options (additive-only is non-negotiable).