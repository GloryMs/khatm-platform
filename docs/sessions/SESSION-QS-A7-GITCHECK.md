# SESSION-QS-A7-GITCHECK — quick investigation session (khatm-platform, IntelliJ)

> **Type:** quick session, **investigation/report-only**. No production code changes.
> **Repo:** `khatm-platform` (IntelliJ, Claude Code plugin).
> **Prereq gate for:** building staging images for the Vault deployment (see STATE
> "DECISION REVERSAL (2026-08-13)" entry) — staging deploy waits on this session's report.
> **Duration target:** ≤ 1 hour.

---

## Why this session exists (context, read first)

1. **A7 (from GAMEDAY-2.3.3 evidence, `khatm-docs/evidence/GAMEDAY-2.3.3/SUMMARY.md`):**
   the retro-captured staging audit trail contains the successful forced retire
   (`KEY_RETIRED khatm-default:key-1 {"forced": true}`) but **no `KEY_RETIRE_REJECTED`
   row anywhere in the window**, despite STATE's attestation describing "staged retire
   stopping at KH-KEY-0422 by design" as executed. Two hypotheses, undecidable from SQL
   alone:
   - **H1:** the KH-KEY-0422 guard throws synchronously **before** any
     `audit.record(...)` call — guard fired correctly, silence is by construction.
   - **H2:** the staged (non-forced) retire was never actually attempted against
     staging in that window.
   This session decides H1 vs H2 **by reading source**, nothing else.
2. **Git verification:** STATE records **three interactive hotfixes (2026-08-11/12)
   applied directly on `main`, "not yet committed"** at the time of writing:
   - `shared.TenantContext#runAsDefaultTenant` + wrapped `audit.record` calls in
     `CredentialController#verify` / `ClaimRedemptionService#redeem`
     (+ new `rbac.AuthenticatedCallerOnAnonymousEndpointsTest`),
   - `SchemaAuthoringService#createVersion` max-version fix
     (+ new `CredentialSchemaRepository#findMaxVersionByTenantIdAndCode`),
   - `IssueRequest.schemaId` version pin (+ `SchemaCatalog#requirePublishedById`,
     + kept tests `IssuanceSchemaVersionPinTest`, `IssueRequestJsonTest`,
     `rbac.IssueWithSchemaIdOverHttpTest`).
   They are *believed* to have landed via **PR #56** (implementation brief), but
   believed ≠ verified. Staging images must be built from merged `main` only.

---

## I-phase (investigate — the whole session is I-phase)

### Part 1 — Git state verification

1. `git status` — report the working tree state verbatim. **If dirty: report the exact
   file list and STOP the git part there** (see V2). Do not stash, clean, checkout,
   or commit anything.
2. `git fetch origin && git log --oneline -15 origin/main` and
   `git rev-parse HEAD origin/main` — report whether local `main` == `origin/main`.
3. `gh pr view 56 --json title,state,mergedAt,mergeCommit,files` — list PR #56's files.
4. Cross-check: for **each** of the three hotfixes above, confirm its named affected
   files (per STATE's own lists) appear in a merged commit on `origin/main` — via the
   PR #56 file list and/or `git log --oneline -- <path>` + `git show <sha> --stat`.
   Specifically confirm on `origin/main`:
   - [ ] `shared/TenantContext.java` contains `runAsDefaultTenant`
   - [ ] `credential/web/CredentialController.java` + `credential/domain/
         ClaimRedemptionService.java` wrap their `audit.record` in it
   - [ ] `rbac/AuthenticatedCallerOnAnonymousEndpointsTest.java` exists (2 tests)
   - [ ] `schema/domain/SchemaAuthoringService.java#createVersion` uses
         `findMaxVersionByTenantIdAndCode(...) + 1` (NOT `source.getVersion() + 1`)
   - [ ] `credential/api/IssueRequest.java` carries `schemaId`;
         `SchemaCatalog#requirePublishedById` exists;
         `IssuanceSchemaVersionPinTest` / `IssueRequestJsonTest` /
         `rbac/IssueWithSchemaIdOverHttpTest` all exist on `main`
5. `gh pr list --state open` — confirm zero open PRs (or list them).
6. Verdict line: **"staging-image gate: PASS/FAIL"** — PASS only if working tree clean
   (or dirty with files provably unrelated to the three hotfixes), local == origin,
   and all checkboxes above confirmed on `origin/main`.

### Part 2 — A7: the KH-KEY-0422 rejection path

1. Locate the guard: `grep -rn "KH-KEY-0422" src/` — expected in the `key` module
   (likely `key.domain.KeyLifecycleService` retire path and/or its web layer).
   Read the full retire method(s): the staged/non-forced branch specifically.
2. Answer, with file/line citations:
   - [ ] Does ANY `audit.record(...)` (or equivalent) execute on the **rejection**
         branch before the KH-KEY-0422 exception propagates?
   - [ ] Does `AuditAction` (enum or equivalent) even define `KEY_RETIRE_REJECTED`,
         or was that value only ever hypothesized in the evidence brief's SQL comment?
   - [ ] If an audit write DOES exist on that branch: could it be lost anyway
         (e.g. same transaction as the throw → rolled back)? Check the transactional
         boundaries around it (`@Transactional` placement, exception type).
3. Map the answer to the hypotheses:
   - No audit call on the rejection path (or the action value doesn't exist) → **H1
     confirmed**: guard is correct-but-silent; the staging audit trail's silence is
     expected; A7 can close as-is with this citation.
   - Audit call exists and should have persisted → **H2 (or a rollback loss)**: the
     absence in staging means the staged retire likely wasn't attempted there (or the
     line was rolled back with the 409) — report which, with the transactional
     evidence.
4. **Do not change behavior.** If H1 and adding a `KEY_RETIRE_REJECTED` audit line
   seems trivially easy — still don't (see V1). Rejection-path auditing is a behavior
   decision (new audit action value, i18n-adjacent docs, error-codes docs) that goes
   through Majd.

---

## Deliverable / close-out

- A single report in-session (chat) with: the git verdict line, the A7 verdict
  (H1/H2 + citations), and any surprises.
- **Docs-only change allowed:** append the findings to `docs/STATE.md` under the
  `QS-A7-GITCHECK` open item (branch `chore/qs-a7-gitcheck-record`, PR only — never
  merge; Majd merges). If the working tree was dirty (V2 fired), skip the branch/PR
  entirely and deliver the report in chat only.

## Veto points (defaults apply if Majd is unreachable)

- **V1 — A7 remediation:** if H1, do we add a `KEY_RETIRE_REJECTED` audit line?
  **Default: NO — report only.** (If Majd later wants it, it becomes a scoped item in
  SESSION-KH-2.4x, with tests + docs/error-codes + STATE, not a quick-session edit.)
- **V2 — dirty working tree:** if `git status` is not clean, do we commit/stash/clean?
  **Default: NO — report the exact paths and stop the git part.** Uncommitted
  interactive-hotfix remnants are Majd's to disposition.

## Self-stop gates

- STOP if `docs/STATE.md` lacks the "DECISION REVERSAL (2026-08-13)" entry (means the
  updated STATE wasn't pulled in — this brief depends on it).
- STOP (git part) on a dirty working tree, per V2.
- STOP if PR #56 is not merged or its file list contradicts the checkboxes — report
  the discrepancy; do not "fix" it.
- No production code edits, no test edits, no reruns of anything against staging.
  This session touches staging zero times.
