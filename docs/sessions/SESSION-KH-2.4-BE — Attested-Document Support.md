# SESSION-KH-2.4-BE — Attested-Document Support (platform micro) — rev. 2026-08-10

> **Repo:** khatm-platform · **Spec:** FS-2.4 (§4 contract surface) · **Size:** micro (≤ 1 day)
> **Scheduling:** Game-day KH-2.3.3 **executed & passed** (Option A split scope; see the
> GAMEDAY record in `docs/STATE.md`). The standing "no platform session before Game-day"
> rule is satisfied. **Preamble 1 verifies the record exists — if it doesn't, self-stop:**
> that means the STATE push didn't land, not that the rule changed.
> **Contract discipline:** additive-only, as always.

---

## 0. Preamble (mandatory, self-stop on failure)

1. **Game-day gate (verify, don't assume):** `docs/STATE.md` on `origin/main` must contain
   the "GAMEDAY KH-2.3.3 — EXECUTED & PASSED" record. Absent → self-stop and report
   (Majd pushes it via admin override; do not paste it yourself).
2. Branch off latest `origin/main`; `mvn verify` must be green before any change.
3. **CI health check (updated):** the July billing outage is believed resolved (Trivy
   warmup fixed in PR #52; khatm-console CI fully green post-cleanup). Confirm GitHub
   Actions actually starts and runs green on a trivial push to the session branch. If
   jobs still fail-to-start on billing grounds, **stop and report** — a real blocker now,
   not the expired waiver.
4. Verify-first: read the current `claims_def` validation capabilities in the `schema`
   module before assuming anything about item 3 below. Record findings before writing.

## 1. Scope

### Item 1 — `requires_attestation` on `credential_schema`
- Additive Flyway migration: `credential_schema.requires_attestation boolean NOT NULL
  DEFAULT false`. `MigrationImmutabilityTest` and clean-boot must stay green.
- Expose the flag additively on the existing schema admin read/write surfaces
  (`schema:manage`) so the console can set and filter on it. **Verify it also appears on
  whatever schema-list surface the console's issue wizard reads** (C9 will gate the
  attested flow off this flag at selection time) — if that's the same surface, just
  record it; if it's a different read model, extend it additively too.

### Item 2 — `attestation` object on the issuance request
- `POST /api/v1/credentials` request gains an **optional** `attestation` object:
  `{ "note": string (optional, ≤ 500 chars) }`. The attesting operator is the
  authenticated principal — never a request field.
- **Enforcement, deny-by-default in both directions:**
    - Schema has `requires_attestation=true` and `attestation` absent → `400`, new error
      code `KH-ATT-0400` (attestation required for this schema).
    - Schema has `requires_attestation=false` and `attestation` present → `400`,
      `KH-ATT-0401` (attestation not applicable to this schema). No silent ignoring.
- On success, write audit line `SCAN_ATTESTED` (actor = principal, entity_ref = the
  credential `ref`, detail = `{ "note": ... }` if provided — never claim values) in the
  **same transaction** as issuance, ordered before `CREDENTIAL_ISSUED`. Per SEC §9 the
  detail carries metadata only; the document hash lives in the credential claims, not in
  the audit detail.
- Bulk issuance path (`/credentials/bulk`): out of scope for attested schemas —
  reject attested schemas on bulk with `KH-ATT-0402`. (FS-2.4 scope: the portal is a
  single-document, human-attested flow by definition.)

### Item 3 — `pattern` validation in `claims_def` (conditional)
- If the verify-first reading (preamble 4) shows `claims_def` already supports regex/
  pattern constraints, do nothing and record that. Otherwise add a `pattern` validator
  to the schema validation path (additive to the `claims_def` JSON shape).

### Item 4 — Seed + bundles + docs
- `local`/`dev` seeder: example schema `AttestedDocument/v1` — `requires_attestation=true`,
  claims: `doc_sha256` (pattern `^[0-9a-f]{64}$`), `doc_type` (i18n-labeled),
  `original_issue_date`, `attestation_note` (optional). All fields in `_sd` (FS-0.4 D1 —
  structural, not optional).
- New `KH-ATT-*` message keys in **both** bundles (EN/AR) — Arabic wording goes to Majd's
  review as usual. `docs/error-codes.md` updated.

### Item 5 — STATE hygiene (bundled housekeeping, zero code)
- Delete the expired **"CI status (temporary) — ignore red CI through 2026-07-31"**
  section from `docs/STATE.md` — its own text mandates removal once CI is confirmed
  green again, which preamble 3 establishes in this very session. If preamble 3 found CI
  still broken, leave the section and record that instead (it becomes a live blocker
  entry, not a waiver).

## 2. Out of scope
- Any file upload endpoint (forbidden by FS-2.4 D1 — the file never reaches the platform).
- Any new signing machinery, endpoint, or scope (FS-2.4 D2/D8).
- Console work (SESSION-C9).
- **The two open contract asks (`MeResponse` tenant slug; 2FA-enrollment signal + TOTP
  status field) — deliberately NOT in this session.** They ship as their own follow-up
  micro (SESSION-KH-2.4x) right after this merges. Do not fold them in here.

## 3. Tests (named, all must pass alongside the full existing suite)
1. Attestation enforcement, all four quadrants (required×present, required×absent,
   not-required×present, not-required×absent) → correct code or success.
2. Audit ordering: `SCAN_ATTESTED` precedes `CREDENTIAL_ISSUED` for the same `ref`,
   same transaction; rollback on post-attestation failure leaves **no** orphan
   `SCAN_ATTESTED` line.
3. Bulk rejection of an attested schema → `KH-ATT-0402`.
4. Pattern validation: malformed `doc_sha256` (wrong length, uppercase, non-hex)
   rejected at issuance with the standard schema-validation error envelope.
5. `MessageBundleParityTest`, `MigrationImmutabilityTest`, `ModulithBoundariesTest`,
   `RepositoryDefaultTransactionsTest`, `CrossTenantIsolationTest`, `ConcurrentConsumeTest`
   — the standing six, untouched and green.

## 4. DoD — machine-verifiable (this session closes on these)
- `mvn verify` green, full suite + new tests; CI genuinely running and green (preamble 3).
- Published `openapi.json` diff is additive-only: `attestation` request object,
  `requires_attestation` on schema surfaces, `KH-ATT-04xx` codes.
- Live compose e2e: issue an `AttestedDocument/v1` credential via curl with attestation
  → verify passes; audit shows both lines in order.
- `docs/STATE.md` updated before session close (including Item 5's section removal).

## 5. DoD — Majd walkthrough (formally owned by Majd, post-merge gate as configured)
- Arabic review of the new `KH-ATT-*` bundle keys (hard merge gate as always —
  review happens on the PR before merge; main now requires a PR, so this fits the
  protection setup naturally).
