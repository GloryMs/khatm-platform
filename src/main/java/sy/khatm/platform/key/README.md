# key

Issuer key management and cryptographic signing (ES256). Key material never leaves this
module (SEC §9) — other modules depend only on `key :: api` (`KeySigner`, `KeyVerifier`, and, as
of KH-2.1, two more deliberately narrow surfaces below — never the full `KeyProvider`/rotation
contract).

**Events in:** none. **Events out:** `KeyRotated` (KH-2.3a, extended with a `provider` field in
KH-2.3b) — consumed by `status.worker.KeyRotationHandler` (forces the rotated tenant's status
lists stale) and `tenant.worker.TenantKeyProviderSyncHandler` (keeps `tenant.key_provider` in
sync). `key` itself never depends on either module — it only publishes.

**Tables owned:** `issuer_key`. Populated and rotated as of KH-0.5: each `KeyProvider` persists
the generated key's public JWK here, keyed by `kid`, alongside `provider` (which backend created
it) and `provider_ref` (that backend's opaque pointer — a PKCS#12 keystore alias for `SoftKeyProvider`,
a transit key name for `VaultTransitProvider`). Private key material is never written to this table.

**Shape (spec FS-0.5 §2, extended KH-2.3b spec FS-2.3 D5/D6):**
- `api/` — `KeySigner` (sign) + `KeyVerifier` (resolve a public key strictly by `kid`, no
  fallback), plus two KH-2.1 additions (spec FS-2.1 D6/D8): `TenantKeyProvisioner
  #provisionFirstKey` (idempotent — the tenant-onboarding admin plane's "give this new tenant its
  first ACTIVE key" step, always on the platform-default provider (`SOFT`), `tenant.domain
  .TenantAdminService`'s one caller) and `JwksLookup #publishableKeys` (a tenant's publishable
  JWKS material, called by `tenant.web.TenantJwksController`'s per-tenant JWKS endpoint —
  deliberately *not* in this module's own `web/`, since `tenant` already depends one-way on this
  `api` package for onboarding; a reverse `key → tenant :: api` dependency to resolve a slug would
  be a Modulith cycle). These are the *only* cross-module surfaces; `ModulithBoundariesTest`
  enforces this.
- `domain/` — module-private:
  - `KeyProvider` — SPI for the physical crypto backend (generate / sign / resolve public key
    against an opaque `providerRef`). Every implementation registers itself under a bean name
    (`"SOFT"`/`"VAULT"`); `KeyLifecycleService` holds all of them in a name-keyed map and resolves
    the right one per `issuer_key` row's own `provider` column — never a single ambient default
    (KH-2.3b superseded D3's original "one provider, config-swapped" shape with "every configured
    provider registered, resolved per key," spec V3's per-tenant `tenant.key_provider` column).
  - `SoftKeyProvider` — always registered: a single PKCS#12 keystore file on disk, alias == `kid`.
    Fails startup (never silently recovers) on a missing passphrase outside `local`, or a wrong
    passphrase against an existing keystore.
  - `VaultTransitProvider` (KH-2.3b) — registered only when `khatm.keys.vault.enabled=true`.
    HashiCorp Vault Transit engine, keys created `exportable=false` (private material never
    leaves Vault), signing via Vault's own `transit/sign` (requesting `marshaling_algorithm=jws`
    for the raw fixed-length signature JWS needs — see its own Javadoc for the "jws" vs "jose"
    naming gotcha this session caught against a real Vault). A connectivity failure at
    generate/sign time throws `IntegrityException` `KH-KEY-0503` — never a silent SOFT fallback.
  - `KeyLifecycleService` — owns `issuer_key` persistence, the `PENDING → ACTIVE → RETIRING →
    RETIRED` state machine, the one-`ACTIVE`-per-tenant invariant (`issuer_key_one_active`
    partial index), and provider resolution (above). `rotate(tenantId, tenantSlug)` stays on the
    current provider; `rotate(tenantId, tenantSlug, provider)` is the provider-migration
    mechanism (spec D6 — a migration is nothing but a rotation with an explicit provider). Records
    `KEY_CREATED` / `KEY_ROTATED` via `shared :: audit`'s `AuditService`.
  - `KeyBootstrap` — an `ApplicationRunner` that idempotently provisions the default tenant's
    first `ACTIVE` key on startup (always `SOFT`), in every profile (production included — there
    is no key without it). **This is temporary by design**: Phase 2 replaces auto-provisioning
    with an explicit administrative ceremony once a console/RBAC exists to gate it. Do not extend
    `KeyBootstrap` to provision additional tenants.
- `web/` — `JwksController`: `GET /.well-known/jwks.json` — since KH-2.1, a **deprecated alias for
  the default tenant only** (spec V2, stays through Phase 2); every other tenant's JWKS is at
  `GET /t/{tenantSlug}/.well-known/jwks.json` (`tenant.web.TenantJwksController`). Calls
  `KeyLifecycleService#publishableKeysForDefaultTenantJwks` — named distinctly from the `JwksLookup`
  cross-module override (`#publishableKeys`) since both wrap the same query but return different
  types (module-private `PublishedKey` vs. `key.api.PublishedKeyView`).
  `SigningKeyStatusController` (KH-1.1.5-BE, spec FS-1.5.4 #4): `GET /api/v1/admin/signing-keys`,
  every key regardless of state (including `RETIRED`), lifecycle fields including `provider` via
  `KeyLifecycleService#listAllStatuses` — no JWK material, no new `key :: api` surface (gated on
  the `key:manage` scope, spec FS-2.2 D2, entirely inside this module).
  `SigningKeyRotationController` (KH-2.3a/b): `POST /api/v1/admin/signing-keys/rotate` (optional
  `provider` body field — the migration mechanism, spec D6) and `POST
  /api/v1/admin/signing-keys/{kid}/retire`, both `key:manage`, tenant-scoped only.

**`kid` format:** `{tenant-slug}:key-{seq}` (e.g. `khatm-default:key-1`) — provider-independent;
the same sequence continues across a provider migration. Verification resolves strictly by `kid`
— an unknown or `RETIRED` `kid` is always `bad_signature`; there is no fallback to "the current
active key" (SEC §3). Resolution reads `issuer_key.public_jwk` directly (KH-2.3b) — it never calls
a `KeyProvider`, so verification never depends on a KMS-class provider being reachable.

**Status:** production-shaped for a single-tenant deployment (KH-0.5); KH-2.1 adds tenant
onboarding + per-tenant JWKS lookup surfaces; KH-2.3a adds provider-agnostic rotation/retirement;
KH-2.3b adds the first real KMS-class provider (Vault Transit) alongside SOFT. HSM-backed
persistence remains KH-3.1.
