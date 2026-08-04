Your gate actions (order matters)
PR #23: review → Arabic gate on any new bundle keys → merge. This makes the status-list fields real for the console's already-defensive Verify screen.
Console C1 PR: do the 10-minute visual pass yourself before merging (platform stack up + console dev or container → click through Verify and Revoke in both languages — the session honestly couldn't). Then merge. Issue's absence from routes is by design; C1b completes it.
After the platform session below merges: console C1b runs.
The remaining contract gap — folded into the next platform session

The fix for SchemaSummary/SchemaDetail is additive and small (expose code, plus sdFields/defaultMaxUses/defaultValidity so the brief's prefill requirement becomes achievable instead of worked-around). Rather than a second mini-session, it rides as Part A of the KH-1.4.3 session — one PR, one review, and KH-1.4.3 closes platform v1. Two small decisions I've made in the brief under your standing approval: allowed_schemas empty/NULL = deny-all (deny-by-default, consistent with your CONVENTIONS §7 posture — the seeder gets updated accordingly), and /consume explicitly requires a CONSUMING_PARTY-owned key (closing an ambiguity FS-0.6b left).

Created 2 files, read a file
Created 2 files, read a file

Two briefs, strictly ordered — the platform session must merge before C1b starts (its step 2 hard-stops if the enriched schema types aren't in the refreshed contract).

Run sequence: merge #23 → merge console C1 (after your visual pass) → run SESSION-KH-1_4_3-plus-schema-contract → review/merge (Arabic gate: the new consume-denial code's keys) → run SESSION-C1b-issue-screen → merge. At that point something worth pausing on happens: platform v1 is declared complete, and the console can execute the entire operator loop — pick a schema, issue, mint, render the QR that a wallet will someday scan, verify, revoke, and watch the status list update. The full demo exists with only the wallet missing from the story.

Which makes the next advisory deliverable obvious: FS-W0 + the wallet's first feature spec (scan QR v1 → redeem → store → display with selective disclosure — the client side of FS-1.2.1 D2/D8, including the store-before-display obligation D4 wrote into the contract). Your third repo opens exactly when your review load on the console lane drops off, per the two-lane rule. Send me the platform session's output — its honest report on whether KH-1.4.1/1.4.2 (persistent idempotency + the 50-parallel-consumers test) are already covered or still owed will tell us whether platform v1 closes clean or with one small debt, and then the wallet package comes with the same message.