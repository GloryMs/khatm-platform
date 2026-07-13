# connector

Outbound webhook and integration connectors — delivers platform events (credential issued,
consumed, revoked) to external subscriber endpoints, plus the non-automated issuer gateway
(KH-2.4) that stores only content hashes, never document content (P1 rule).

**Events in:** `CredentialIssued`, `CredentialConsumed`, `CredentialRevoked` (future — KH-1.3).
**Events out:** none yet.

**Tables owned:** `webhook_subscription`, `webhook_delivery`.

**Status:** stub — implementation deferred to KH-1.x. Not part of the KH-0.2.1 baseline
schema (spec FS-0.2 does not define these tables yet).
