# key

Issuer key management and cryptographic signing (ES256). Key material never leaves this
module (SEC §9) — other modules depend only on `key :: api` (`KeySigner`, `KeyVerifier`).

**Events in:** none. **Events out:** none yet (`KeyRotated` — future, once rotation gets an
admin-triggered path in KH-2.2).

**Tables owned:** `issuer_key`. Populated and rotated as of KH-0.5: `SoftKeyProvider` persists
each generated key's public JWK here, keyed by `kid`, alongside `provider_ref` (its PKCS#12
keystore alias). Private key material is never written to this table.

**Shape (spec FS-0.5 §2):**
- `api/` — `KeySigner` (sign) + `KeyVerifier` (resolve a public key strictly by `kid`, no
  fallback). The *only* cross-module surface; `ModulithBoundariesTest` enforces this.
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
    tests only until RBAC-gated admin rotation lands in KH-2.2. Writes `KEY_CREATED` /
    `KEY_ROTATED` rows directly to `audit_log` (minimal form — the full audit write path is
    KH-0.6).
  - `KeyBootstrap` — an `ApplicationRunner` that idempotently provisions the default tenant's
    first `ACTIVE` key on startup, in every profile (production included — there is no key
    without it). **This is temporary by design**: Phase 2 replaces auto-provisioning with an
    explicit administrative ceremony once a console/RBAC exists to gate it. Do not extend
    `KeyBootstrap` to provision additional tenants.
- `web/` — `JwksController`: `GET /.well-known/jwks.json`, `ACTIVE` + `RETIRING` public keys
  only, `Cache-Control: max-age=300`, no auth.

**`kid` format:** `{tenant-slug}:key-{seq}` (e.g. `khatm-default:key-1`). Verification resolves
strictly by `kid` — an unknown or `RETIRED` `kid` is always `bad_signature`; there is no
fallback to "the current active key" (SEC §3).

**Status:** production-shaped for a single-tenant deployment (KH-0.5). KMS/HSM-backed
persistence is KH-2.3 → KH-3.1; per-tenant JWKS paths are KH-2.1.3.
