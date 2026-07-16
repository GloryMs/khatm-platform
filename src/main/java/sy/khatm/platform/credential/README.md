# credential

Core lifecycle of verifiable credentials: issue, verify, consume (atomic single-use
decrement), revoke. Stores only cryptographic proofs and status metadata — never document
content or PII (P1 rule) — as of KH-0.4, that rule is enforced by the token's own structure
(SD-JWT), not just storage policy.

**Events in:** none yet. **Events out:** `CredentialIssued`, `CredentialConsumed`,
`CredentialRevoked` (future — KH-1.3).

**Tables owned:** `credential`, `consumption_event`, `claim_code`.

**SD-JWT (KH-0.4, spec FS-0.4) — the two decisions worth understanding before touching this
module:**
- **D1 — every `claims_def` field becomes a salted disclosure, no exceptions.** There is no
  "this claim can stay explicit" escape hatch: `credential.signed_payload` carries only D3's
  structural fields (`iss`, `iat`, `nbf`, `exp`, `vct`, `ref`, `status`) plus `_sd`/`_sd_alg` —
  digests only. `SdJwtIssuanceStructuralTest` asserts this directly against the *persisted*
  row, not just the in-memory response.
- **D2 — `sd_fields` changed meaning.** Same DB column (`credential_schema.sd_fields`,
  unchanged since FS-0.2), new semantics: no longer "hidden fields" (D1 already hides
  everything) but **"fields the holder may withhold at presentation time."** Every
  `claims_def` field *not* in `sd_fields` is mandatory — `CredentialService#verify` rejects
  (`withheld_mandatory_claim`) a presentation missing one. `DemoSeeder`'s demo schema splits
  its three fields this way on purpose (`result` mandatory; `caseNumber`/`issuedAt`
  withholdable) so both directions are exercised.

Built on `com.authlete:sd-jwt` (D4) for disclosure/digest construction only — signing still
happens exclusively through `KeySigner` (`key :: api`), completely unchanged from KH-0.5.
`CredentialService#issue` returns the full tilde-separated SD-JWT presentation
(`<jwt>~<d1>~..~<dn>~`, D6) as a one-time delivery; it is never persisted in that form.
`CredentialService#verify` accepts that same format, or a bare compact JWT (treated as a
zero-disclosure presentation — typically, correctly, rejected by the mandatory check unless
`sd_fields` covers every field). `#issueClaimCode` now actually encrypts the disclosures
(AES-256-GCM via `ClaimsEncryptionService`, key from `khatm.claims.enc-key`) into
`disclosures_enc` — this closes the encryption half of the `disclosures_enc` blocker opened
at FS-0.2; only the expiry-zeroing worker (KH-1.2.1, needs the ADR-09 worker skeleton) remains
open.

KH-0.2.1's schema/holder/status-list resolution and KH-0.5's strict-by-`kid` verification are
otherwise unchanged: `CredentialService#issue` still resolves `schema_id` (`schema :: api`),
`holder_id` (`holder :: api`), and `(status_list_id, status_idx)` (`status :: api`) before
persisting; `#consume` still resolves `consuming_party_id` (`consumer :: api`) and always
writes a durable `idempotency_key` (Redis is only the fast-path cache). The atomic-consume
invariant (`CredentialRepository#consumeOne`) is unchanged: a single conditional `UPDATE`
ensures exactly one concurrent consumer wins.

**Error handling & i18n (KH-0.6a, spec FS-0.6a):** `CredentialService#verify` now returns
`VerifyReason` codes (`shared :: error`) instead of raw string literals — a verification
failure is still always a `200` domain result, never a thrown exception (D1); `#checkSignature`
now distinguishes `unknown_kid` (missing/unresolvable `kid`) from `bad_signature` (resolved key,
bad signature bytes), a split the old single boolean check couldn't express. `#issue` wraps a
`KeySigner` `JOSEException` as `IntegrityException(KH-KEY-0500)` instead of propagating a
checked exception. `CredentialController` resolves `VerifyResponse.reasonMessage` (localized)
via `MessageSource` + the request's `Accept-Language` and throws `NotFoundException` instead of
building `ResponseEntity.notFound()` by hand — `GlobalExceptionHandler` (`shared :: web`) is now
the only place that builds an error response. `IssueRequest.holderRef` and `VerifyRequest.sdJwt`
gained `@NotBlank`.

**Cross-module dependencies:** `key :: api`, `schema :: api`, `holder :: api`,
`status :: api`, `consumer :: api`, `shared` (open root package), `shared :: error`
(`KhatmException` subtypes, `VerifyReason`), `shared :: web` (`ErrorEnvelope`, OpenAPI-only).
