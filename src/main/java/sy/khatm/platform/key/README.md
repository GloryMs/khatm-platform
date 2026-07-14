# key

Issuer key management and cryptographic signing (ES256). Key material never leaves this
module (SEC §9) — other modules depend only on `key :: api` (`KeySigner`).

**Events in:** none. **Events out:** `KeyRotated` (future — KH-0.5).

**Tables owned:** `issuer_key` (created by the KH-0.2.1 baseline migration; not yet
populated — `SoftKeyService` generates an ephemeral in-memory ES256 key pair at startup and
loses it on restart). KMS/HSM-backed persistence via `issuer_key` is KH-0.5.

**Status:** demo/dev signing only. Do not use in production until KH-0.5 lands.
