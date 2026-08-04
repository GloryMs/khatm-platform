Gate work (you, ~30 min):

PR #21: paste the ThrottledException follow-up instruction into the IntelliJ session → review the small commit → Arabic review of the two error.clm.* keys → merge.
Console PR #1: merge → run the 10-minute live verification (platform up, console up, login → schemas → RTL flip). If anything breaks there, that's a C1-blocking finding — tell me before starting C1.
khatm-docs housekeeping: FS-1.2.1 (already in the platform mirror), FS-1.3, both with APPROVED status.

Lane 1 — IntelliJ: SESSION-KH-1_3.md implements FS-1.3. Notable in the spec: D3 puts the bit-flip inside the revoke transaction (truth advances atomically) while re-signing/publishing goes async through your existing ADR-09 pipeline — no new mechanisms, the worker infrastructure earns its keep. D5's debounced catch-up condition means a revocation storm produces one re-sign, not twenty-five. And DoD-2 makes NFR-06 your first CI-measured NFR — a timed test, not a promise in a document.

Lane 2 — VSCode: SESSION-C1-feature-screens.md builds the three operator screens. Two things I fixed in advance because they would have bitten: the QR's api value is env-configurable with a visible warning when it's localhost (your physical-Android-device lesson, now encoded before the wallet exists to suffer from it), and the verify screen renders the status-list fields optionally — so the two lanes can merge in either order without breaking each other. Note step 3's hard gate: C1 refreshes the vendored contract first and stops if /claims/redeem isn't in it — meaning merge PR #21 before starting C1.

After both lanes land: KH-1.4.3 closes platform v1 on one side, and FS-W0 opens the wallet repo on the other — at that point all the wallet needs (claim endpoint, QR contract, status-list URI) exists on main, which was the whole point of the sequencing. Send me either session's output when it's back.