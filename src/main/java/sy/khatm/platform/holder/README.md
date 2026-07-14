# holder

Pseudonymous holder identity registry. `pseudoRef` is an alias supplied by the issuing
organisation's own system — never a real name or national ID (P1 rule).

**Events in:** none. **Events out:** none yet.

**Tables owned:** `holder`.

**Status:** KH-0.2.1 adds persistence plus one cross-module method,
`HolderDirectory#ensureHolder`, which finds or registers a holder by pseudonymous reference.
`wallet_jwk` (key-binding public key) stays unpopulated until Phase 3 wallet binding.
