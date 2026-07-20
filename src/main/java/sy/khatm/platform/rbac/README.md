# rbac

Console session auth, API keys, and role-based access control (spec FS-0.6b).

**Events in:** none. **Events out:** none.

**Tables owned:** `app_user`, `role`, `user_role` (seeded by `V1__baseline.sql`; Java persistence
lands here in KH-0.6b), `api_key` (new, `V2__auth_api_keys.sql`).

**Shape (spec FS-0.6b §3):**
- `api/` — `CurrentActor` + `CurrentActorResolver`. The *only* cross-module surface;
  `credential.domain.CredentialService#consume` (KH-1.4.3) is the first real consumer, reading
  `CurrentActor#ownerId()` to enforce `consuming_party_schema` scoping.
- `domain/` — module-private:
  - `AppUser` / `Role` / `ApiKey` — JPA entities matching V1 + V2.
  - `AuthService` — login/logout support: argon2id password check, the Redis-TTL lockout counter
    (D6, independent of the administrative `LOCKED` status), and D7's single generic failure
    message for every reason.
  - `ApiKeyService` — create/revoke/verify. Key shape `khk_<env>_<prefix>.<secret>` (D2); SHA-256
    of the secret (D4 — a fast hash is safe here because the secret's own entropy is the real
    defense, unlike a human password).
  - `AdminBootstrap` — same idempotent-`ApplicationRunner` pattern as `key.domain.KeyBootstrap`
    (spec FS-0.5 §5), applied to "does any `app_user` exist yet." No silent default outside
    `local` (D10).
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
- `web/` — `AuthController`: `POST /api/v1/auth/login` · `POST /api/v1/auth/logout` · `GET
  /api/v1/auth/me` · `POST /api/v1/admin/api-keys` · `POST /api/v1/admin/api-keys/{id}/revoke`.
- `seed/` — `DemoApiKeySeeder` (`local`/`dev` only, `@Order(2)`): a demo `CONSUMING_PARTY` API key,
  logged once in full so a developer can exercise `/consume` without a real onboarding flow.
  KH-1.4.3: also allowlists the demo party for `credential.seed.DemoSeeder`'s demo schema
  (`CriminalRecordExtract/v1`, resolved via `SchemaCatalog#listAll` by code) — `DemoSeeder` runs
  first (`@Order(1)`) so that schema always exists by the time this seeder needs it. The console
  admin itself needs no separate demo seeder — `AdminBootstrap` already provisions one in every
  profile, `local` included.

**Scope catalog (D5, unchanged from V1's seed):** `issue`, `verify`, `consume`, `revoke`, `admin`.

**Status:** KH-0.6b — completes Phase 0. Full admin console (user/role management UI) is KH-2.2.
`consuming_party_schema` enforcement building on the `CONSUMING_PARTY` API-key principal is done
(KH-1.4.3, lives in `credential.domain.CredentialService#consume` + `consumer :: api`).
