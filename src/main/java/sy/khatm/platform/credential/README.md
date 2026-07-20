# credential

Core lifecycle of verifiable credentials: issue, verify, consume (atomic single-use
decrement), revoke, and redeem a one-time wallet claim code. Stores only cryptographic proofs
and status metadata — never document content or PII (P1 rule) — as of KH-0.4, that rule is
enforced by the token's own structure (SD-JWT), not just storage policy.

**Claim delivery (KH-1.2.1, spec FS-1.2.1) — closes the `disclosures_enc` blocker for good.**
`POST /api/v1/claims/redeem` is the wallet-facing exchange: lock the code row (`SELECT ... FOR
UPDATE`), validate (not claimed, not expired, still has encrypted disclosures), decrypt,
deliver, set `claimed_at`, zero `disclosures_enc` — all one transaction, no grace window. It
authenticates by possessing the code alone (spec §9) — `rbac.security.SecurityConfig`'s third
public endpoint (alongside `/verify` and JWKS) — guarded instead by
`ClaimRedeemThrottleService`'s per-IP fixed-window counter (D6). Every failure flavor (unknown,
malformed, expired, already claimed, expiry-zeroed) collapses to the identical `404
KH-CLM-0404` (D5, anti-probing); the throttle is the one outcome audited individually
(`CLAIM_REDEEM_THROTTLED`, IP + count). The `SELECT ... FOR UPDATE` lock is what makes a redeem
race-safe against `ClaimCodeExpiryWorker`'s concurrent sweep touching the same row — together
the two paths guarantee `disclosures_enc` always ends up `NULL` exactly once, either on claim
or on expiry, never both, never neither.

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
`status :: api`, `consumer :: api`, `rbac :: api` (KH-1.4.3 — `CurrentActorResolver`), `shared`
(open root package), `shared :: error` (`KhatmException` subtypes, `VerifyReason`), `shared :: web`
(`ErrorEnvelope`, OpenAPI-only).

**Schema-scoped consumption (KH-1.4.3, spec SEC §7):** `CredentialController#consume` calls
`CredentialService#enforceSchemaAllowlist` *before* `#consume` itself — deliberately outside
`#consume`'s `@Transactional` boundary, the same shape `ClaimRedeemThrottleService#enforce` uses
ahead of `ClaimRedemptionService#redeem`, and for the identical reason: the denial-path audit row
must commit on its own, not roll back alongside the exception that's about to unwind the stack
(discovered empirically — nesting the check inside `#consume` silently discarded every
`CONSUME_SCHEMA_DENIED` row). A `CONSUMING_PARTY` API key may only consume a credential whose
schema is in its own `consuming_party_schema` allowlist (`consumer :: api #isSchemaAllowed`) —
deny-by-default, so an unconfigured party can consume nothing. Denial is a new `KH-CNS-0403`
(deliberately distinct from the generic `KH-RBC-0403` — "authenticated but this schema isn't
yours" is its own, support-relevant situation) and a new `CONSUME_SCHEMA_DENIED` audit row
(`entityRef` = the credential's ref, `detail` = `schemaId` + `party`, never claims material).
`SecurityConfig`'s existing `ScopeGuard.requireScopeAndConsumingPartyKey("consume")` rule already
rejects a `TENANT` key here regardless of scope — this session only adds the explicit test proving
it, no new enforcement code was needed for that half.
