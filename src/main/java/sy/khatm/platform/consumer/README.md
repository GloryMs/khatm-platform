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

**Schema scoping (KH-1.4.3):** `ConsumingPartyRegistry#isSchemaAllowed`/`#allowSchema` back the
`consuming_party_schema` join table (no JPA entity — a bare composite-key join table, same
treatment `rbac`'s `user_role` gets). `credential.domain.CredentialService#consume` calls
`#isSchemaAllowed` before its atomic update: a `CONSUMING_PARTY`-authenticated caller may only
consume a credential whose `schema_id` has a `consuming_party_schema` row for their own party —
deny-by-default, so a party with an empty (or entirely absent) allowlist can consume nothing.
`rbac.seed.DemoApiKeySeeder` calls `#allowSchema` to scope the demo consuming party to the demo
schema, otherwise every local demo consume would 403 under this default.

**Admin plane (KH-1.4.4):** `ConsumingPartyAdmin` (behind `/api/v1/admin/consuming-parties`,
`consumer:manage` scope, spec FS-2.2 D2) registers parties, flips `ACTIVE`↔`SUSPENDED`, and manages
the schema allowlist. `V5` adds a
`code` column (deterministic id derivation was always `UUID.nameUUIDFromBytes("tenant:code")`, but
the code itself was never persisted); `create` produces the same row `#ensure` would, so explicit
creation and implicit ensure never diverge — a duplicate code is `KH-CNS-0409`, not a second row.
A `SUSPENDED` party's keys fail authentication (`ConsumingPartyRegistry#isActive`, consulted by
`rbac`'s `ApiKeyService#verify`), exactly like a revoked key. Key minting (`POST /{id}/api-keys`)
lives in `rbac.web`, not here — only `rbac` may create `api_key` rows, and `consumer → rbac` would
form a module cycle. `#ensure`'s find-or-create race is closed (KH-1.4.4 D6): the entity forces a
true INSERT (`Persistable`), and a lost race re-reads the winner's row (the method holds no
enclosing transaction, so the failed insert never poisons the follow-up SELECT). Per-party quotas
and rate limits remain future work.
