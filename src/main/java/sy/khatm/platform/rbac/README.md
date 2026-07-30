# rbac

Console session auth, API keys, and role-based access control (spec FS-0.6b).

**Events in:** none. **Events out:** none.

**Tables owned:** `app_user`, `role`, `user_role` (seeded by `V1__baseline.sql`; Java persistence
lands here in KH-0.6b), `api_key` (new, `V2__auth_api_keys.sql`), `user_totp_recovery_code` (new,
`V13__totp_2fa.sql`, KH-2.2c — `app_user` also gained `totp_secret_enc`/`totp_enrolled_at`/
`totp_confirmed_at` in the same migration).

**Shape (spec FS-0.6b §3):**
- `api/` — `CurrentActor` + `CurrentActorResolver`, and (KH-1.1.5-BE, spec FS-1.5.4 D2)
  `ApiKeyOwnerLookup` + `ApiKeyOwnerRef`. The *only* cross-module surface; `credential.domain
  .CredentialService#consume` (KH-1.4.3) is the first `CurrentActorResolver` consumer, reading
  `CurrentActor#ownerId()` to enforce `consuming_party_schema` scoping. `ApiKeyOwnerLookup`
  batch-resolves a *historical* `audit_log.actor_id` (an `api_key.id`) to its owner — a gap
  `CurrentActorResolver` can't fill since it only ever resolves the current request's actor;
  `credential.web`'s activity/consuming-party-stats endpoints are its first callers.
- `domain/` — module-private:
  - `AppUser` / `Role` / `ApiKey` — JPA entities matching V1 + V2.
  - `AuthService` — login/logout support: argon2id password check, the Redis-TTL lockout counter
    (D6, independent of the administrative `LOCKED` status), and D7's single generic failure
    message for every reason. `login` takes an optional `tenantSlug` (spec FS-2.2 — multi-tenant
    console login): blank/absent resolves to the caller's ambient default tenant unchanged; an
    unknown or `SUSPENDED` slug gets the identical generic D7 failure as bad credentials.
  - `TenantProvisioningService` — the `platform:admin` cross-tenant provisioning orchestration:
    tenant onboarding's rbac-side half (role catalog + optional first `TENANT_ADMIN`), and adding/
    listing users of a tenant other than the caller's own, both via `shared.OnBehalfOfExecutor`.
  - `ApiKeyService` — create/revoke/verify. Key shape `khk_<env>_<prefix>.<secret>` (D2); SHA-256
    of the secret (D4 — a fast hash is safe here because the secret's own entropy is the real
    defense, unlike a human password).
  - `ApiKeyOwnerLookupImpl` (KH-1.1.5-BE, new) — implements `ApiKeyOwnerLookup` over
    `ApiKeyRepository#findAllById`, no new query.
  - `AdminBootstrap` — same idempotent-`ApplicationRunner` pattern as `key.domain.KeyBootstrap`
    (spec FS-0.5 §5), applied to "does any `app_user` exist yet." No silent default outside
    `local` (D10).
  - `TotpService` (KH-2.2c, spec FS-2.2 V1) — enroll/confirm (self-service), the login-time
    code/recovery-code check, and the admin reset. `TotpAlgorithm` (RFC 6238, hand-rolled — no new
    dependency, Base32 via the already-pinned `bcprov-jdk18on`) and `TotpSecretEncryptionService`
    (AES-256-GCM, own `khatm.auth.totp.enc-key`, a dedicated sibling of `credential.domain
    .ClaimsEncryptionService` rather than a cross-module reuse) back it.
- `security/` — Spring Security wiring:
  - `SecurityConfig` — one `SecurityFilterChain`. Public: `POST /verify`, `GET
    /.well-known/jwks.json` (D9). Everything else needs a valid session or API key;
    `ScopeGuard`'s per-route `AuthorizationManager`s add the scope/actor-kind requirement spec §3
    names for `/issue`, `/revoke`, `/consume`, `/api/v1/admin/**`.
  - `ApiKeyAuthFilter` — authenticates `Authorization: Bearer khk_...` for the current request
    only; never persists to a session (API-key paths are stateless by construction, not by
    special-casing — see the class Javadoc for why).
  - `SessionAuthenticator` — the one place a console login becomes a real `HttpSession`-backed
    `Authentication` (login), and the one place a session is torn down (logout).
  - `KhatmAuthenticationEntryPoint` / `KhatmAccessDeniedHandler` — write the same `ErrorEnvelope`
    shape `shared.web.GlobalExceptionHandler` produces, independently, because both run *before*
    `DispatcherServlet` and so `GlobalExceptionHandler` (an `@RestControllerAdvice`) never sees
    these denials.
  - `TotpEnrollmentEnforcementFilter` (KH-2.2c) — the exact `PasswordChangeEnforcementFilter`
    shape, wired right after it: walls off every endpoint except enroll/confirm/`/me`/logout for a
    session holding a mandatory-2FA scope with no active TOTP.
- `web/` — `AuthController`: `POST /api/v1/auth/login` · `POST /api/v1/auth/logout` · `GET
  /api/v1/auth/me` · `POST /api/v1/auth/totp` (KH-2.2c, completes a login challenge) · `POST
  /api/v1/admin/api-keys` · `POST /api/v1/admin/api-keys/{id}/revoke`. `UserAdminController` also
  gains `POST /users/me/totp/enroll`/`/confirm` (self-service) and `POST /users/{id}/totp/reset`
  (KH-2.2c); `TenantProvisioningController` gains the on-behalf-of `POST
  /admin/tenants/{id}/users/{userId}/totp/reset`.
- `seed/` — `DemoApiKeySeeder` (`local`/`dev` only, `@Order(2)`): a demo `CONSUMING_PARTY` API key,
  logged once in full so a developer can exercise `/consume` without a real onboarding flow.
  KH-1.4.3: also allowlists the demo party for `credential.seed.DemoSeeder`'s demo schema
  (`CriminalRecordExtract/v1`, resolved via `SchemaCatalog#listAll` by code) — `DemoSeeder` runs
  first (`@Order(1)`) so that schema always exists by the time this seeder needs it. The console
  admin itself needs no separate demo seeder — `AdminBootstrap` already provisions one in every
  profile, `local` included.

**Scope catalog (KH-2.2a, spec FS-2.2 D1 — replaces the coarse `admin` scope, clean cut, spec V3):**
`issue`, `verify`, `consume`, `revoke`, `schema:manage`, `consumer:manage`, `key:manage`,
`tenant:admin`, `platform:admin` — see `security/ScopeRegistry`. Deny-by-default: an endpoint
without a declared scope fails `security/AdminPathScopeCoverageTest`.

**Status:** KH-0.6b completed Phase 0's session/API-key auth. KH-2.2a replaced the `admin` scope
stand-in with the granular registry above and re-gated every `/api/v1/admin/**` endpoint
accordingly. KH-2.2b added tenant user management + onboarding completion. KH-2.2c (this session)
added TOTP 2FA — mandatory for `revoke`/`tenant:admin`/`platform:admin`/`key:manage` holders (spec
FS-2.2 V1). `consuming_party_schema` enforcement building on the `CONSUMING_PARTY` API-key
principal is done (KH-1.4.3, lives in `credential.domain.CredentialService#consume` + `consumer ::
api`).
