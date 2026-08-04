# SESSION-KH-2.4-BE — Attested-Document Support (platform micro)

> **Repo:** khatm-platform · **Spec:** FS-2.4 (§4 contract surface) · **Size:** micro (≤ 1 day)
> **Scheduling:** runs **after Game-day KH-2.3.3** (standing rule: no platform session before it).
> **Contract discipline:** additive-only, as always.

---

## 0. Preamble (mandatory, self-stop on failure)

1. Branch off latest `origin/main`; `mvn verify` must be green before any change.
2. **CI health check (new, one-time):** the "ignore red CI" waiver expired 2026-07-31.
   Confirm GitHub Actions now actually starts and runs on a trivial push to the session
   branch. If jobs still fail-to-start on billing grounds, **stop and report** — that is
   now a real blocker, not the waived condition.
3. Verify-first: read the current `claims_def` validation capabilities in the `schema`
   module before assuming anything about item 3 below. Record findings before writing.

## 1. Scope

### Item 1 — `requires_attestation` on `credential_schema`
- Additive Flyway migration: `credential_schema.requires_attestation boolean NOT NULL
  DEFAULT false`. `MigrationImmutabilityTest` and clean-boot must stay green.
- Expose the flag additively on the existing schema admin read/write surfaces
  (`schema:manage`) so the console can set and filter on it.

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
- If the verify-first reading (preamble 3) shows `claims_def` already supports regex/
  pattern constraints, do nothing and record that. Otherwise add a `pattern` validator
  to the schema validation path (additive to the `claims_def` JSON shape).

### Item 4 — Seed + bundles + docs
- `local`/`dev` seeder: example schema `AttestedDocument/v1` — `requires_attestation=true`,
  claims: `doc_sha256` (pattern `^[0-9a-f]{64}$`), `doc_type` (i18n-labeled),
  `original_issue_date`, `attestation_note` (optional). All fields in `_sd` (FS-0.4 D1 —
  structural, not optional).
- New `KH-ATT-*` message keys in **both** bundles (EN/AR) — Arabic wording goes to Majd's
  review as usual. `docs/error-codes.md` updated.

## 2. Out of scope
- Any file upload endpoint (forbidden by FS-2.4 D1 — the file never reaches the platform).
- Any new signing machinery, endpoint, or scope (FS-2.4 D2/D8).
- Console work (SESSION-C9).

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
- `mvn verify` green, full suite + new tests; CI genuinely running and green (preamble 2).
- Published `openapi.json` diff is additive-only: `attestation` request object,
  `requires_attestation` on schema surfaces, `KH-ATT-04xx` codes.
- Live compose e2e: issue an `AttestedDocument/v1` credential via curl with attestation
  → verify passes; audit shows both lines in order.
- `docs/STATE.md` updated before session close.

## 5. DoD — Majd walkthrough (formally owned by Majd, post-merge)
- Arabic review of the new `KH-ATT-*` bundle keys (hard merge gate as always —
  review happens on the PR before merge).