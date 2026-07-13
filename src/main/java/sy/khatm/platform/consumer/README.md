# consumer

Verifier/consuming-party registry — organisations permitted to verify or consume credentials.

**Events in:** none. **Events out:** none yet.

**Tables owned:** `consuming_party`, `consuming_party_schema`.

**Status:** KH-0.2.1 adds persistence plus one cross-module method,
`ConsumingPartyRegistry#ensure`, which finds or registers a party by a caller-supplied code,
deriving a stand-in `api_key_hash` from that code (SHA-256) so `credential.CredentialService
#consume` can record a `consumption_event` end-to-end. Real API-key issuance/onboarding,
per-party quotas, and schema scoping via `consuming_party_schema` are KH-1.4.3.
