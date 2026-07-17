# consumer

Verifier/consuming-party registry — organisations permitted to verify or consume credentials.

**Events in:** none. **Events out:** none yet.

**Tables owned:** `consuming_party`, `consuming_party_schema`.

**Status:** KH-0.2.1 adds persistence plus one cross-module method,
`ConsumingPartyRegistry#ensure`, which finds or registers a party by a caller-supplied code,
deriving the row's id deterministically from `(tenant, code)` so the same code always resolves
to the same row. KH-0.6b removed the KH-0.2.1 stand-in `api_key_hash` column entirely
(`V2__auth_api_keys.sql`) — real API-key authentication for a consuming party now lives in
`rbac`'s `api_key` table (`owner_type = CONSUMING_PARTY`), unrelated to this find-or-create path.
Per-party quotas and schema scoping via `consuming_party_schema` remain KH-1.4.3.
