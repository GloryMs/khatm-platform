# holder

Pseudonymous holder identity registry. `pseudoRef` is an alias supplied by the issuing
organisation's own system — never a real name or national ID (P1 rule).

**Events in:** none. **Events out:** none yet.

**Tables owned:** `holder`.

**Status:** KH-0.2.1 adds persistence plus one cross-module method,
`HolderDirectory#ensureHolder`, which finds or registers a holder by pseudonymous reference.
KH-2.8.2 adds `HolderDirectory#findById` (read-only, RLS-scoped) so an idempotent issuance replay
can report the `holderRef` a credential was issued to.
`wallet_jwk` (key-binding public key) stays unpopulated until Phase 3 wallet binding.
