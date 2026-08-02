# Runbook — Signing-Key Rotation & Retirement

> Spec `docs/specs/FS-2.3-kms-key-rotation.md` D2/D3/D4/D8. Written in KH-2.3a (provider-agnostic,
> SOFT), extended in KH-2.3b with Step 1b (the Vault provider-migration path), executed literally
> in KH-2.3.3 (game-day). Steps 1-4 below apply unchanged regardless of provider — rotation is
> provider-agnostic by design (spec D1).

## Why rotation is roll-FORWARD-only — no rollback section

There is deliberately no "how to undo a rotation" section below. A rotation is triggered for one
of two reasons: routine key hygiene, or a suspected/confirmed compromise of the current `ACTIVE`
key's private material. In **neither** case is "roll back to the previous key" the safe direction:

- If the previous key is suspected compromised, rolling back to it re-activates the very key you
  were trying to get away from.
- If the rotation was routine, the previous key is already `RETIRING` — it is still fully valid
  for verification, still published in JWKS, and not going anywhere. There is nothing to restore;
  it never stopped working.

The only correct remedy for "the rotation itself went wrong" (e.g. the new key was generated with
a defect) is **another rotation** — generate a third key, forward again. Retirement (`RETIRING` →
`RETIRED`) is the one truly one-way step, and the min-age guard (spec V4) exists precisely to make
sure that step is never taken in haste.

## Preconditions

- Caller holds the `key:manage` scope (console session or API key — either actor kind).
- You know the tenant whose key you are rotating (rotation always acts on the caller's own
  ambient tenant — there is no cross-tenant rotation endpoint, spec V3's per-tenant provider
  column is out of scope until KH-2.3b).

## Step 1 — Rotate

```
POST /api/v1/admin/signing-keys/rotate
```

Atomically: a new key is generated via the configured `KeyProvider`, the current `ACTIVE` key
moves to `RETIRING`, and the new key becomes `ACTIVE`. Response is the new key's `kid`/`state`/
`validFrom`.

**Verification checkpoint 1** — confirm the new key is live and the old one is still published:

```
GET /api/v1/admin/signing-keys
```

Expect exactly one `ACTIVE` row (the new `kid`) and the previous `ACTIVE` key now `RETIRING`.
Every prior `RETIRED` key, if any, is still listed too — nothing is ever silently dropped from
this view.

```
GET /.well-known/jwks.json   (or GET /t/{tenantSlug}/.well-known/jwks.json for a non-default tenant)
```

Expect **both** the new key and the just-retired one present (`ACTIVE` + `RETIRING` + any
`RETIRED` keys all stay published — spec FS-0.2 §3.2 / FS-2.3 D2/D4). If the old `kid` is missing
here, STOP — this is the exact failure mode that would break every already-issued document's
verification, and it means something is wrong with the JWKS endpoint, not with rotation itself.

**Verification checkpoint 2** — an already-issued document still verifies:

Pick any credential issued before this rotation (or issue one now, before rotating again in a
later step) and confirm:

```
POST /api/v1/credentials/verify
```

still returns `valid:true` for it. This is the whole point of `RETIRING` staying published —
verification must not notice a rotation happened.

**Verification checkpoint 3** — new issuance uses the new key:

Issue a fresh credential and confirm its JWS header's `kid` matches the new `ACTIVE` key from
Step 1's response — not the old one.

## Step 1b — Migrating a tenant onto a different provider (KH-2.3b, spec D5/D6)

There is no separate "migrate to Vault" endpoint or ceremony — spec D6's own framing is that
provider migration **is** Step 1, with one addition: an explicit `provider` field.

```
POST /api/v1/admin/signing-keys/rotate
{ "provider": "VAULT" }
```

Everything from Step 1 onward applies unchanged — one `ACTIVE` (now Vault-backed), the previous
key `RETIRING` on whatever provider it was created on (commonly `SOFT`), both published in JWKS,
both still verifiable. `GET /api/v1/admin/signing-keys` now shows a `provider` column per key —
confirm the new row reads `VAULT` and the retiring row still reads its original provider.

**Verification checkpoint 1b** — the old, pre-migration credential still verifies (repeat
checkpoint 2 above) with **Vault never involved**: `KeyVerifier#resolvePublicKey` resolves every
key's public material from `issuer_key.public_jwk` directly (written once at key-creation time),
never by calling back into whichever `KeyProvider` created it. This means `GET
/.well-known/jwks.json`, the per-tenant JWKS endpoint, and `POST /api/v1/credentials/verify` all
keep working even if Vault is unreachable at the moment you check — only **new** signing (new
issuance, or a status-list resign) needs Vault reachable, since only that touches the private key.

**Verification checkpoint 1c** — fail-closed, never a silent downgrade: if you want to prove the
fail-closed guarantee directly (safe to skip on a live tenant), stop the Vault backend and attempt
another rotation with `{"provider":"VAULT"}` or issue a fresh credential for a Vault-active tenant.
Expect `503 KH-KEY-0503` (`key.provider-unavailable`), not a credential silently issued under a
freshly-created `SOFT` key — a silent fallback would be a key-security downgrade the platform must
never perform on its own initiative. Restart Vault and confirm normal operation resumes with no
further action needed (no key state was left inconsistent by the failed attempt — the tenant's
previously-`ACTIVE` key, whichever provider it is on, is still `ACTIVE`).

An un-registered provider name (a typo, or naming `VAULT` on a deployment that never set
`khatm.keys.vault.enabled=true`) is rejected the same way rotation requests are always validated —
`400 KH-KEY-0400` (`key.unknown-provider`), before any key is touched.

## Step 2 — Confirm the status lists re-sign with the new key

Rotation forces every one of the tenant's status lists stale in the same operation (spec D3). The
existing periodic sweep (`khatm.status.publish.debounce`, default 2000ms) re-signs each within one
cycle — no separate action needed here, only verification:

```sql
SELECT list_code, signed_artifact FROM status_list WHERE tenant_id = '<tenant-id>';
```

Decode each `signed_artifact`'s JWS header and confirm `kid` is the new key from Step 1. If a list
is still showing the old `kid` after a few seconds, check the worker image is actually running
(`khatm.worker.enabled=true`) — the sweep is a worker-role component, and this is the same class
of "worker isn't running" gap that would silently stall the near-real-time revoke path too.

## Step 3 — Wallet kid-selection (manual, on-device — spec D7)

This step is verified, never assumed:

1. Issue a credential under the **old** kid (before rotating, or reuse one from before Step 1).
2. Rotate (Step 1).
3. On a real wallet device, present/verify that credential.

Expect: the wallet resolves the JWKS, finds the entry matching the credential's own `kid` header,
and verifies successfully — **not** by picking the first key in the JWKS array. If the wallet
fails this (verifies only when its own `kid` happens to be first, or fails once a second key is
present), **stop here** — this is a wallet-side defect, not a platform one. Record it as a W5 ask
in `docs/STATE.md` and do not attempt a platform-side workaround (there is no platform-side fix
for a wallet that ignores `kid` — weakening JWKS back to one key defeats rotation entirely).

## Step 4 — Retire the old key (only once you mean it)

```
POST /api/v1/admin/signing-keys/{kid}/retire
```

Only a `RETIRING` key can be retired — `ACTIVE` (409 `KH-KEY-0409`) and already-`RETIRED` keys
(also 409) are rejected outright.

**Expected rejection before the guard window elapses:**

```
422 KH-KEY-0422  key.retiring-too-young
```

`details`/message carries the remaining wait (default guard: `khatm.keys.min-retiring-age`,
`P30D`). This is the expected, correct outcome for a key retired sooner than 30 days after
rotation — it is not an error to investigate, it is the guard working.

**Deliberate early retirement** (e.g. confirmed compromise, or a lab/game-day run that can't wait
30 days):

```json
{ "force": true }
```

This bypasses the guard and is **always audited** — the `KEY_RETIRED` row carries
`detail.forced=true`, distinguishing a deliberate override from a routine one after the fact.

**Verification checkpoint 4** — retirement never breaks verification:

Confirm the same credential from Step 1's checkpoint 2 **still** verifies (`valid:true`) after
retirement. `RETIRED` keys stay published in JWKS and stay resolvable — retiring only stops a key
from ever signing anything new again; it does not stop it from verifying what it already signed.

## Full checklist (game-day, KH-2.3.3)

- [ ] `POST /rotate` → new `ACTIVE`, old `RETIRING`
- [ ] `GET /admin/signing-keys` shows both, correct states
- [ ] JWKS shows both keys
- [ ] Pre-rotation credential still verifies
- [ ] New issuance carries the new `kid`
- [ ] Status lists re-signed with the new `kid` (within one sweep cycle)
- [ ] Wallet verifies the old credential from a real device, selecting by `kid` (or a W5 ask is
      recorded and this step is marked as a known gap, not silently skipped)
- [ ] Early retire attempt → `422 KH-KEY-0422` with remaining wait
- [ ] Forced retire → `200`, audited `forced:true`
- [ ] Old credential still verifies after retirement (`RETIRED` still in JWKS)
- [ ] SOFT→Vault migration: `POST /rotate {"provider":"VAULT"}` → new `ACTIVE` reads `provider:
      VAULT` in `GET /admin/signing-keys`, old key's own provider unchanged
- [ ] Pre-migration credential still verifies with Vault stopped (public-read paths never call a
      `KeyProvider`)
- [ ] Vault stopped → new issuance/rotation attempt → `503 KH-KEY-0503`, never a silent SOFT
      fallback; Vault restarted → normal operation resumes with no cleanup needed
