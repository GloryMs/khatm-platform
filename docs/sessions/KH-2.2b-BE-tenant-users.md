Session: feat/KH-2.2b-BE-tenant-users — spec FS-2.2 (APPROVED, §4 final), decisions D5+D6+D8.
Branch off latest origin/main (must include KH-2.2a merge — verify via git log; the new scope
registry and OnBehalfOfExecutor are this session's foundation; self-stop if absent).
Sonnet only (rbac module).

VERIFY-AGAINST-CODE FIRST (report before writing):
- app_user/role/user_role live shape (FS-0.2 §3.10 baseline + anything later migrations touched),
  the password-hash mechanism on the login path (argon2id per spec — confirm), and how a user's
  roles are loaded into the session principal.
- The plaintext-once display pattern used for API keys (KH-1.1) — the temporary-password flow
  must reuse it, not invent a sibling.
- Whether a "must change password at first login" flag exists anywhere; if not, it's yours to add
  (column on app_user via new migration).

BUILD:
1. D5 tenant-staff user management, all gated tenant:admin, all tenant-scoped via context (RLS
   backstop already live):
   - GET /api/v1/users (list, newest first) ; POST /api/v1/users (username, displayName_i18n
     EN+AR, roles[] from the fixed seeded catalog) -> temporary password, plaintext-once response.
   - POST /api/v1/users/{id}/roles (replace role set) ; POST /{id}/lock | /unlock | /disable ;
     POST /{id}/reset-password -> new temporary password, plaintext-once, forces change-at-login.
   - Custom roles out of scope (fixed catalog of the three seeded roles per tenant).
2. LAST-ADMIN GUARD (D5): atomically reject (409 KH-USR-0423) any lock/disable/role-change that
   would leave the tenant with zero ACTIVE users holding tenant:admin. Race-proof it:
   ConcurrentLastAdminTest — two concurrent locks against the final two admins => exactly one
   succeeds (joins the race-test family; same harness style as ConcurrentConsumeTest).
3. Forced password change: temporary-password logins succeed only into a change-password step
   (POST /api/v1/users/me/password); every other authenticated call while the flag is set => 403
   with a distinct error code so the console can route to the change screen. Flag cleared on change.
4. D6 onboarding completion:
   - POST /admin/tenants accepts optional initialAdmin {username, displayName_i18n} -> creates
     the tenant's first TENANT_ADMIN, temp password plaintext-once in the response. Follows the
     existing resumable-onboarding semantics (retry of a half-onboarded slug may now also need to
     create the missing admin — extend the resume logic, don't fork it).
   - POST /admin/tenants/{id}/users — same creation shape for EXISTING tenants, platform:admin
     via OnBehalfOfExecutor (enumerated-caller list extended + its exact-match test updated),
     audited ON_BEHALF_OF.
5. D8 errors/audit: KH-USR-0400/0404/0409/0423 (+ the forced-change code from step 3);
   AuditAction.USER_{CREATED,ROLES_CHANGED,LOCKED,UNLOCKED,DISABLED,PASSWORD_RESET}; entityRef =
   username. All new user.* message keys in BOTH bundles same commit — Arabic gate applies.
6. New migration (V11) only for what's genuinely new (e.g. must_change_password flag); V1–V10
   untouched; immutability/clean-boot/checksum-lock green. Contract regenerated via its test;
   additive-only expected this session.

DoD: mvn verify green (report N/N incl. ConcurrentLastAdminTest). Live compose e2e: create tenant
WITH initialAdmin -> login with temp password -> forced change -> create a schema under own tenant
(schema:manage works) -> create a second user (ISSUER_OPERATOR) -> that operator can issue but
gets 403 on schema writes and on /api/v1/users -> attempt to disable the sole TENANT_ADMIN => 409
-> platform-admin adds a user to the OTHER tenant via /admin/tenants/{id}/users, audit shows
ON_BEHALF_OF. PR opened NOT merged; Arabic gate on user.* keys = merge blocker; STATE updated.
Self-stop: resume-onboarding extension turns out to conflict with existing semantics -> stop and
present options; any ambiguity about which principal states count as "ACTIVE admin" for the
guard -> stop and ask.