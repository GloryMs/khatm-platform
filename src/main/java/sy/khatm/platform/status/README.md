# status

Credential status list management — a compact, gzip-compressed Status List 2021-style
revocation bitstring per tenant.

**Events in:** none. **Events out:** `CredentialStatusChanged` (future — KH-1.3).

**Tables owned:** `status_list`.

**Status:** KH-0.2.1 adds persistence plus one cross-module method,
`StatusListAllocator#allocate`, which atomically reserves a `(status_list_id, status_idx)`
pair for a credential being issued — a `SELECT ... FOR UPDATE` row lock serialises concurrent
allocations on the same list, the same pattern `credential.consumeOne` uses for the
atomic-consume invariant. Publishing the signed bitstring artifact, flipping bits on revoke,
and capacity rollover are KH-1.3.
