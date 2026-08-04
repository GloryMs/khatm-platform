Session: feat/KH-2.3a-BE-key-rotation — spec FS-2.3 APPROVED 2026-07-30; veto resolutions final:
V1 Vault Transit (next session, not this one), V2 rotation-first on SOFT (this session),
V3 per-tenant provider column (prepare nothing here; 2.3b owns it), V4 min-age P30D + audited force.
Branch off latest origin/main. Sonnet only (key module).

VERIFY FIRST (report before writing): KeyProvider SPI surface (FS-0.5 vs live code — code wins);
how JWKS builds today (RETIRING/RETIRED must stay published per FS-0.2 — verify current behavior);
how the status-list sweep picks work (artifact_version vs version — the V9 precedent); all signing
call sites (must resolve the ACTIVE key at sign time — grep for any cached kid; caching across
requests is a rotation bug waiting to happen).
ALSO: codify the context-switch-before-transaction pattern (3rd occurrence: ApiKeyService,
TenantAdmin, AuthService#login) as a named rule in docs/CONVENTIONS.md; apply it if the rotation
orchestration needs cross-context work.

BUILD:
1. D2 POST /api/v1/admin/signing-keys/rotate (key:manage): atomically generate new key via SPI ->
   old ACTIVE->RETIRING -> new ACTIVE. The one-active partial index is the final arbiter;
   ConcurrentRotationTest: two simultaneous rotations => exactly one succeeds (race-test family).
2. D3 inside the same rotation op: forced version-bump on ALL the tenant's status lists (runtime
   V9-style) so the existing sweep re-signs them with the new kid within one cycle. Regression
   test: post-rotation sweep artifact carries the NEW kid.
3. D4 POST /api/v1/admin/signing-keys/{kid}/retire: only RETIRING->RETIRED (409 KH-KEY-0409
   otherwise); min-age guard khatm.keys.min-retiring-age (Duration, default P30D) => 422
   KH-KEY-0422 with the remaining wait in details; force=true bypasses, audited as KEY_RETIRED
   with forced=true in details. RETIRED stays in JWKS.
4. D8 AuditAction.KEY_{ROTATED,RETIRED}; KH-KEY-* codes; docs/runbooks/key-rotation.md
   (step-by-step with verification checkpoints; rotation is roll-FORWARD-only — no rollback
   section, document why: rolling back to a possibly-compromised or already-retiring key is
   never the safe direction; the remedy for a bad rotation is another rotation).
5. D7 wallet kid-selection verification (manual, on device): issue under old kid -> rotate ->
   old credential still verifies from the multi-key JWKS. If the wallet picks the first key
   instead of matching kid -> STOP wallet-side, record a W5 ask in STATE, continue platform work.
Message keys EN/AR same commit if any (Arabic gate applies). Contract additive-only.

DoD: mvn verify green (report N/N incl. ConcurrentRotationTest); live compose e2e: issue ->
rotate -> old credential verifies AND new issuance carries new kid -> lists re-signed (new kid
in artifact) -> early retire => 422 with wait time -> force retire (audited) -> old credential
STILL verifies. PR opened NOT merged; STATE updated.