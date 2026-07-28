# key

Issuer key management and cryptographic signing (ES256). Key material never leaves this
module (SEC §9) — other modules depend only on `key :: api` (`KeySigner`, `KeyVerifier`, and, as
of KH-2.1, two more deliberately narrow surfaces below — never the full `KeyProvider`/rotation
contract).

**Events in:** none. **Events out:** none yet (`KeyRotated` — future, once rotation gets an
admin-triggered path in KH-2.2).

**Tables owned:** `issuer_key`. Populated and rotated as of KH-0.5: `SoftKeyProvider` persists
each generated key's public JWK here, keyed by `kid`, alongside `provider_ref` (its PKCS#12
keystore alias). Private key material is never written to this table.

**Shape (spec FS-0.5 §2):**
- `api/` — `KeySigner` (sign) + `KeyVerifier` (resolve a public key strictly by `kid`, no
  fallback), plus two KH-2.1 additions (spec FS-2.1 D6/D8): `TenantKeyProvisioner
  #provisionFirstKey` (idempotent — the tenant-onboarding admin plane's "give this new tenant its
  first ACTIVE key" step, `tenant.domain.TenantAdminService`'s one caller) and `JwksLookup
  #publishableKeys` (a tenant's publishable JWKS material, called by `tenant.web
  .TenantJwksController`'s per-tenant JWKS endpoint — deliberately *not* in this module's own
  `web/`, since `tenant` already depends one-way on this `api` package for onboarding; a reverse
  `key → tenant :: api` dependency to resolve a slug would be a Modulith cycle). These are the
  *only* cross-module surfaces; `ModulithBoundariesTest` enforces this.
- `domain/` — module-private:
  - `KeyProvider` — SPI for the physical crypto backend (generate / sign / resolve public key
    against an opaque `providerRef`). Selected via `khatm.keys.provider`
    (`@ConditionalOnProperty`); swapping `SOFT` for a future `KmsProvider`/`Pkcs11Provider` is a
    config change, not a code change (D3).
  - `SoftKeyProvider` — the only `KeyProvider` today: a single PKCS#12 keystore file on disk,
    alias == `kid`. Fails startup (never silently recovers) on a missing passphrase outside
    `local`, or a wrong passphrase against an existing keystore.
  - `KeyLifecycleService` — owns `issuer_key` persistence, the `PENDING → ACTIVE → RETIRING →
    RETIRED` state machine, and the one-`ACTIVE`-per-tenant invariant (`issuer_key_one_active`
    partial index). `rotate()` is fully implemented but has **no REST endpoint** — called by
    tests only until RBAC-gated admin rotation lands in KH-2.2. Records `KEY_CREATED` /
    `KEY_ROTATED` via `shared :: audit`'s `AuditService` (KH-0.6b — migrated off the KH-0.5
    direct-`JdbcTemplate`-insert stopgap).
  - `KeyBootstrap` — an `ApplicationRunner` that idempotently provisions the default tenant's
    first `ACTIVE` key on startup, in every profile (production included — there is no key
    without it). **This is temporary by design**: Phase 2 replaces auto-provisioning with an
    explicit administrative ceremony once a console/RBAC exists to gate it. Do not extend
    `KeyBootstrap` to provision additional tenants.
- `web/` — `JwksController`: `GET /.well-known/jwks.json` — since KH-2.1, a **deprecated alias for
  the default tenant only** (spec V2, stays through Phase 2); every other tenant's JWKS is at
  `GET /t/{tenantSlug}/.well-known/jwks.json` (`tenant.web.TenantJwksController`). Calls
  `KeyLifecycleService#publishableKeysForDefaultTenantJwks` — named distinctly from the `JwksLookup`
  cross-module override (`#publishableKeys`) since both wrap the same query but return different
  types (module-private `PublishedKey` vs. `key.api.PublishedKeyView`).
  `SigningKeyStatusController` (KH-1.1.5-BE, spec FS-1.5.4 #4): `GET /api/v1/admin/signing-keys`,
  every key regardless of state (including `RETIRED`), lifecycle fields only via
  `KeyLifecycleService#listAllStatuses` — no JWK material, no new `key :: api` surface (gated on the
  `key:manage` scope, spec FS-2.2 D2, entirely inside this module).

**`kid` format:** `{tenant-slug}:key-{seq}` (e.g. `khatm-default:key-1`). Verification resolves
strictly by `kid` — an unknown or `RETIRED` `kid` is always `bad_signature`; there is no
fallback to "the current active key" (SEC §3).

**Status:** production-shaped for a single-tenant deployment (KH-0.5); KH-2.1 adds tenant
onboarding + per-tenant JWKS lookup surfaces. KMS/HSM-backed persistence remains KH-2.3 → KH-3.1.
