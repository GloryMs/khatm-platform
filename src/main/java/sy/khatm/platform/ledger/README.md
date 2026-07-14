# ledger

Merkle proof support over the platform's audit trail. `audit_log` (append-only) is owned by
`shared`, written from every module; this module builds Merkle trees over it.

**Events in:** none — ledger is a sink. **Events out:** none.

**Tables owned:** `merkle_node` (Phase 3; explicitly out of scope for the KH-0.2.1 baseline
schema).

**Status:** stub — implementation deferred to Phase 3.
