# SESSION-CHORE-VAULT-STAGING-RECORD — documentation & config session

> **Type:** chore / documentation. **No production code changes, no behavior changes.**
> **Repos:** `khatm-platform` (IntelliJ) — Part 1; `khatm-deploy` — Part 2.
> **Trigger:** staging Vault deployment + SOFT→VAULT migration completed live 2026-08-15.
> **Duration target:** ≤ 1 hour total.
> **Attached inputs (Majd provides these three files: can be found in: C:\Projects\KHATM-Project\khatm-platform\docs\VAULT-STAGING-RECORD — documentation & config):**
> `khatm-transit-app.hcl`, `deploy-staging-vault-section.md`, `STATE-central-2026-08-16.md`.

---

## Context (read before starting)

Vault is now deployed on bunny Magic Containers staging and the tenant's active signing key
has been migrated to `provider: VAULT` (two further rotations since, both VAULT by
inheritance). Credentials signed under both SOFT and VAULT verify correctly.

One correction came out of it and is the reason this session exists: **`transit/keys/*`
requires the `update` capability**, not just `create` + `read`. The first live migration
failed with a Vault 403 (surfacing as `KH-KEY-0503`) on a transit key name that did not yet
exist. Adding `update` fixed it with no other change. The committed policy file is therefore
wrong as it stands, and so is the reasoning in its header comment.

Likely cause (empirical, not verified against Vault source): Vault's ACL layer only
distinguishes create from update on paths whose backend registers an existence check, and
`transit/keys/:name` appears not to register one.

---

## Part 1 — `khatm-platform` (IntelliJ)

Branch: `chore/vault-staging-record`. **PR only — do not merge.** Branch protection is active
on `platform/main`; Majd is the sole merge gate.

### 1.1 Preflight

1. `git status`. The working tree is expected to be dirty — `docs/STATE.md` carries an
   uncommitted "DECISION REVERSAL (2026-08-13)" entry, and `Dockerfile.postgres` is untracked.
   **Report both verbatim before touching anything.**
2. Confirm `origin/main == c7c3d1b` (PR #56 merge). If not, **STOP and report**.
3. **Do not** stash, clean, or discard the existing `docs/STATE.md` changes — that entry is
   wanted and goes into this PR as-is, with the additions in 1.4 appended to it.

### 1.2 Replace the Vault ACL policy

Replace `docker/vault-policy/khatm-transit-app.hcl` with the attached `khatm-transit-app.hcl`
**verbatim**. Do not re-word the header comment — it deliberately records the empirical finding,
its date, the likely cause, and the scope-widening assessment (`transit/keys/<name>/config` is
reachable under the glob; key material still is not, since `transit/export/*` and `delete` are
not granted).

Then grep the repo for anything that restates the old capability set — test fixtures, compose
files, other docs, session notes — and report every hit. **Fix only non-historical ones**:
current-state docs and config get corrected; past session records and STATE history entries
describe what was true at the time and must not be rewritten.

### 1.3 Update `docs/deploy-staging.md`

Replace the existing "Vault hardening for production" section with the attached
`deploy-staging-vault-section.md` content (the section is renamed — Vault is no longer
production-only). Match the file's surrounding heading levels and style; drop the explanatory
preamble at the top of the attachment, it is instructions to you, not document content.

Check the rest of `deploy-staging.md` for statements this contradicts — particularly anything
asserting staging runs no Vault — and correct those in place.

### 1.4 `docs/STATE.md` additions

Append to the existing DECISION REVERSAL entry (do not create a second one):

- Deployment executed 2026-08-15: custom image `ghcr.io/gloryms/khatm-vault:1.17-mc`, file
  storage at `/data/vault` on the volume shared with Postgres (MC caps the app at 2 volumes),
  initialized 5/3, transit + `khatm-transit` policy + least-privilege app token, env vars set
  on both `khatm-api` and `khatm-worker`.
- Three accepted staging deviations: custom image (MC's `no-new-privileges` breaks the official
  entrypoint's privilege drop), `disable_mlock: true` (no `IPC_LOCK`), non-expiring app token
  (`ttl: 0`; production uses AppRole with a bounded period).
- Migration performed via `POST /api/v1/admin/signing-keys/rotate {"provider":"VAULT"}` —
  **not** from the console, which cannot send a body (see Part 1.5). Two subsequent rotations
  inherited VAULT. SOFT- and VAULT-signed credentials both verify.
- **Part B of GAMEDAY KH-2.3.3 is now proven on a live environment**, not only on local
  hardened compose.
- Fail-closed observed live on staging three times during diagnosis (explicit VAULT request +
  Vault unavailable → `503 KH-KEY-0503`, no silent SOFT key, previous ACTIVE key intact) —
  matches the rotation runbook's checkpoint 1c.
- Policy correction as described above, with the note that **any Vault provisioned before
  2026-08-15 from the old policy file needs the policy re-applied**.
- Operational standing item: every pod redeploy re-seals Vault → issuance stops with
  `KH-KEY-0503` until manual unseal; public reads unaffected. Check `sys/seal-status` before
  diagnosing any `KH-KEY-0503`.
- Diagnostic note: `KH-KEY-0503` conflates three distinct causes (sealed, network, 403
  permission) because `VaultTransitProvider` maps them all through `unavailable(...)`.
- Two diagnostic SOFT rotations (`key-5`, `key-6`) were created while diagnosing — recorded so
  they are not later misread as unexplained activity.

Also add, under open items: console cannot select a provider when rotating (Part 1.5), and the
staging console image is not reproducible from git (its `Dockerfile`/`nginx.conf` live in a
scratchpad outside version control) — the latter becomes blocking whenever console deployment
to bunny resumes. **Console deployment to bunny is postponed by Majd's decision (2026-08-16)
— record it, do not schedule it.**

### 1.5 Record the console gap as a platform-side note only

`rotateSigningKey()` in the console sends no request body and the UI has no provider selector,
even though the vendored contract exposes `RotateKeyRequest.provider`. Record this in
`docs/STATE.md` as a known cross-repo gap and add it to the SESSION-KH-2.4x candidate list.
**Do not implement anything in either repo.** If it is built later it must be an explicit,
non-default operator choice with a warning that VAULT means issuance stops when Vault is
unavailable.

### 1.6 `Dockerfile.postgres` — veto point V1

Untracked, unexplained, present in the working tree. Inspect it and report what it appears to
be for. **Default if Majd does not answer: leave it untracked and out of this PR, and record
its existence and apparent purpose in STATE as an item awaiting disposition.** Do not delete it.

### 1.7 Close out Part 1

- No source changes at all. If any `src/**` file would be touched, **STOP and report**.
- `mvn verify` is not required (no code change), but run it if any test resource was touched.
- Open the PR with a body summarising: policy correction + why, docs update, STATE record.
  Do not merge.

---

## Part 2 — `khatm-deploy`

Branch: `chore/vault-staging-files`. PR, do not merge.

Vault is now part of real staging infrastructure, so its definition belongs in version control
rather than in a local folder.

1. Add `vault/Dockerfile` and `vault/vault-config.json` as built and deployed
   (`FROM hashicorp/vault:1.17`, runs as the `vault` user with no privilege transition, config
   baked in at `/vault/config/local.json`, storage `/data/vault`). Ask Majd for the exact files
   from `C:\Projects\KHATM-Project\khatm-deploy\vault\` rather than reconstructing them from
   this brief — **the files on disk are the authority**.
2. Add `vault/unseal-staging-vault.sh` (operator-run, reads unseal keys with echo off, refuses
   non-https addresses, refuses when `VAULT_ADDR` equals the API base, and hard-stops on
   `initialized=false` because that indicates first boot or a detached volume — never
   re-initialize).
3. Add a short `vault/README.md`: what the image is for, why it is custom, the shared-volume
   arrangement, and a pointer to `khatm-platform/docs/deploy-staging.md` as the authoritative
   procedure. Keep the procedure itself in one place only — do not duplicate it here.
4. **No secrets, no endpoint hostnames that would be sensitive to expose, no tokens.** Run
   gitleaks locally if it is wired up in this repo. The Vault config file contains no secrets
   and is safe to commit as-is.

---

## Veto points (defaults apply if Majd is unreachable)

- **V1 — `Dockerfile.postgres`:** commit, delete, or leave? **Default: leave untracked, record
  in STATE.**
- **V2 — scope creep:** if any item here turns out to need a source change, does the session
  proceed? **Default: NO — stop, report, leave it for SESSION-KH-2.4x.**

## Self-stop gates

- STOP if `origin/main != c7c3d1b`.
- STOP before modifying anything under `src/**`.
- STOP if the existing uncommitted `docs/STATE.md` DECISION REVERSAL entry is missing (it is
  this session's anchor).
- STOP if a grep hit for the old policy capabilities sits inside a historical session record —
  report it, do not rewrite history.
- Do not touch staging, Vault, or any running environment. This session is text-only.

## Out of scope — Majd's own follow-ups, not this session's

- **Secret rotation (urgent):** Postgres password, `KHATM_BOOTSTRAP_ADMIN_PASSWORD`,
  `KHATM_AUTH_TOTP_ENC_KEY`, `KHATM_CLAIMS_ENC_KEY` were exposed in plain text during the
  session. The first two rotate directly; the two encryption keys require re-encrypting
  existing data and need a decision with المعماري. Record as a known risk in STATE; do not
  attempt any rotation from this session.
- Re-applying the corrected policy to any Vault instance provisioned before 2026-08-15.
