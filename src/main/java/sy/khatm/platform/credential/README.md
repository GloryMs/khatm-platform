# credential

Core lifecycle of verifiable credentials: issue, verify, consume (atomic single-use
decrement), revoke. Stores only cryptographic proofs and status metadata — never document
content or PII (P1 rule).

**Events in:** none yet. **Events out:** `CredentialIssued`, `CredentialConsumed`,
`CredentialRevoked` (future — KH-1.3).

**Tables owned:** `credential`, `consumption_event`, `claim_code`.

**Status:** KH-0.2.1 rewires `CredentialService#issue` to resolve `schema_id` (`schema ::
api`), `holder_id` (`holder :: api`), and `(status_list_id, status_idx)` (`status :: api`)
before persisting, since all three are now `NOT NULL` foreign keys on `credential`. `#consume`
resolves `consuming_party_id` (`consumer :: api`) the same way, and always writes a durable
`idempotency_key` (Redis is only the fast-path cache). `#issueClaimCode` writes a `claim_code`
row with a SHA-256-hashed one-time code; encrypting real disclosure values into
`disclosures_enc` is KH-1.2.1. The atomic-consume invariant
(`CredentialRepository#consumeOne`) is unchanged: a single conditional `UPDATE` ensures
exactly one concurrent consumer wins.

**Cross-module dependencies:** `key :: api`, `schema :: api`, `holder :: api`,
`status :: api`, `consumer :: api`, `shared` (open root package).
