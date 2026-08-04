Session: feat/KH-2.2d-BE-multitenant-login — closes the two platform gaps recorded by khatm-console
(docs/STATE.md 2026-07-29) that block FS-2.2's exit walkthrough. Branch off latest origin/main.
Sonnet only (auth path). Decision (Majd): login tenant discrimination = optional tenant slug,
backward compatible (absent/blank slug => default tenant; existing sessions unaffected).

VERIFY-AGAINST-CODE FIRST: the exact login path (AuthService + wherever the username lookup pins
the default tenant), how the session principal stores tenant identity for TenantContextFilter,
and SuspendedTenantAuthTest's Javadoc scope note (you'll be deleting that caveat — its scenario
becomes fully testable).

BUILD:
1. Login accepts optional tenantSlug: resolve tenant by slug (unknown/SUSPENDED tenant =>
   the SAME generic auth-failure as bad credentials — no tenant-existence oracle on an
   unauthenticated endpoint, consistent with the unified-404 anti-enumeration stance);
   authenticate the user WITHIN that tenant; principal carries the real tenant; the
   D7/KH-2.1 machinery (TenantContextFilter, RLS, forced-password gate) needs zero special-casing
   — verify that claim with tests rather than asserting it.
2. SUSPENDED-tenant login denial now testable end-to-end: extend SuspendedTenantAuthTest to the
   real HTTP login flow and remove its out-of-scope Javadoc.
3. GET /api/v1/admin/tenants/{id}/users (platform:admin, OnBehalfOfExecutor, enumerated-caller
   list + test updated) — same row shape as GET /api/v1/users.
4. Contract regenerated (additive: optional login field + new GET). New message keys (login form
   label likely lives console-side; expected zero platform keys — if any, both bundles + Arabic gate).
5. RE-RUN THE FS-2.2 EXIT WALKTHROUGH IN FULL, now unblocked, live on compose: create tenant with
   initialAdmin -> login WITH slug + temp password -> forced change -> create schema under own
   tenant -> issue -> create consuming party + key -> consume -> second tenant sees none of it ->
   sole-admin disable => 409 -> suspended tenant's admin login => generic failure. Record in STATE
   as FS-2.2's exit evidence (supersedes the default-tenant workaround note from KH-2.2b).

DoD: mvn verify green (report N/N); walkthrough recorded; PR opened NOT merged; STATE updated
(gap entries closed). Self-stop: if the principal/session shape can't carry non-default tenant
identity without touching session serialization in a breaking way -> stop and present options.