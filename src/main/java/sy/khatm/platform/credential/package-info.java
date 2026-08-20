/**
 * Credential module — core lifecycle of verifiable credentials.
 *
 * <p><b>Responsibilities:</b> issue, verify, consume (atomic single-use decrement), revoke, and
 * redeem a one-time wallet claim code for a credential. Stores only cryptographic proofs and status
 * metadata — never document content or PII (P1 rule). Enforces the atomic-consume invariant:
 * consumption is a single-transaction conditional {@code UPDATE ... WHERE uses_remaining > 0 AND
 * NOT revoked} so that exactly one consumer wins under concurrent load; claim-code redemption is
 * the analogous single-shot invariant for delivery ({@code SELECT ... FOR UPDATE}, spec FS-1.2.1
 * D2).
 *
 * <p><b>SD-JWT (spec FS-0.4):</b> issuance builds a Selective Disclosure JWT, not a plain JWT. Two
 * decisions are worth calling out specifically because they change what "P1" and {@code sd_fields}
 * mean in this module now:
 *
 * <ul>
 *   <li><b>D1 — every {@code claims_def} field becomes a disclosure, no exceptions.</b> There is no
 *       "this one claim is fine to leave explicit" escape hatch. If even one field stayed explicit
 *       in the signed payload, its value would land in {@code credential.signed_payload} — a P1
 *       violation (FS-0.2 D9) baked permanently into a stored, signed artifact. Hiding everything
 *       is the only form that keeps P1 a structural property of the token rather than a
 *       storage-policy promise someone could get wrong later.
 *   <li><b>{@code jwks_uri} claim (KH-2.7-BE, spec FS-0.4 Amendment A1, D3-a).</b> {@code
 *       domain.TenantJwksUriBuilder} (new, module-private, mirrors {@code
 *       status.domain.StatusListUriBuilder}'s shape) bakes {@code
 *       {public-base-url}/t/{tenantSlug}/.well-known/jwks.json} into every issued credential —
 *       self-declared discovery metadata for external verifiers only, never an input to this
 *       platform's own {@code kid -> KeyVerifier} trust decision. Pre-A1 credentials (no {@code
 *       jwks_uri}) verify identically; their fallback is the deprecated default-tenant-only alias
 *       ({@code key.web.JwksController}, kept alive on purpose for exactly this).
 *   <li><b>D2 — {@code sd_fields} no longer means "hidden fields."</b> Since D1 already hides every
 *       field, {@code credential_schema.sd_fields} (unchanged column) is redefined to mean "fields
 *       the holder is <em>permitted to withhold</em> at presentation time." Every {@code
 *       claims_def} field <em>not</em> in {@code sd_fields} is mandatory — {@link
 *       sy.khatm.platform.credential.domain.CredentialService#verify} rejects (reason {@code
 *       withheld_mandatory_claim}) any presentation missing one. This gives the issuer a "mandatory
 *       minimum" (e.g. document number and name always shown; birth date optional).
 * </ul>
 *
 * <p><b>Exposed API:</b> {@code api/} sub-package — DTO records only at this stage. Cross-module
 * service interface will be added when another module requires programmatic access (KH-1.x).
 *
 * <p><b>Search (KH-1.1.4):</b> {@code CredentialService#search} backs {@code GET
 * /api/v1/credentials} — a console-facing, paginated, filtered list over {@code CredentialSummary}
 * rows (proof/status metadata only, never claim content). {@code pseudoRef} filtering resolves
 * against {@code holder :: api}'s {@code HolderDirectory#findByPseudoRef} (added alongside this),
 * never a direct join across the module boundary.
 *
 * <p><b>Idempotency race closure (KH-1.4.1/1.4.2):</b> {@code domain.AtomicConsumptionRecorder}
 * (new, module-private) isolates the eligibility decrement + {@code consumption_event} insert +
 * audit row into their own fresh transaction, so a concurrent double-submit sharing one {@code
 * idempotencyKey} rolls back cleanly on the loser's side (the {@code idempotency_key UNIQUE}
 * constraint) instead of surfacing a generic {@code KH-SYS-0500} or double-decrementing {@code
 * uses_remaining}. See {@code CredentialService#consume}'s Javadoc for why this had to be a
 * separate bean, not a private method.
 *
 * <p><b>Bulk issuance (KH-1.1.3):</b> {@code domain.BulkIssuanceService} (new, module-private) is a
 * separate bean from {@code CredentialService} so that each batch row's call to {@code
 * CredentialService#issue} — and, when requested, {@code #mintClaimCode} — goes through Spring's
 * real transactional proxy rather than a self-invocation; one bad row never rolls back the rest of
 * the batch. No parallel issuance logic exists — every guard {@code #issue} enforces (schema
 * published, signing) applies identically per row. {@code POST /api/v1/credentials/bulk} is capped
 * at 200 items, one schema per batch.
 *
 * <p><b>Dashboard v2 (KH-1.1.5-BE, spec FS-1.5.4):</b> hosts three of the four new console
 * read-endpoints — deliberately <em>not</em> {@code shared.web} as the session brief first
 * suggested, since {@code shared} has no outbound dependencies on any other module (its own
 * package-info states this) and {@code credential}/{@code consumer}/{@code rbac}/{@code key} all
 * already declare {@code shared} as an allowed dependency; {@code shared} depending back on any of
 * them would be a structural cycle {@code ModulithBoundariesTest} rejects outright. This module
 * already declares every cross-module dependency the composition needs (see below), so it hosts
 * them instead:
 *
 * <ul>
 *   <li>{@code web.ActivityController}, {@code domain.ActivityService} (module-private, spec #2,
 *       {@code GET /api/v1/activity}) — resolves both open design points the brief flagged: {@code
 *       CREDENTIAL_CONSUMED}/{@code CREDENTIAL_REVOKED}'s bare-id {@code entity_ref} is resolved to
 *       the credential's {@code ref} via this module's own {@code CredentialRepository} (spec D3,
 *       no cross-module call needed); consuming-party attribution resolves {@code actor_id} (an
 *       {@code api_key.id}) via {@code rbac :: api}'s new {@code ApiKeyOwnerLookup} (spec D2), then
 *       to a display name via {@code consumer :: api}'s {@code ConsumingPartyAdmin#list()} (spec
 *       D4, reused as-is). Deliberately scoped to credential-lifecycle-relevant actions only — not
 *       a general audit-trail viewer across every module's events (spec D1b).
 *   <li>{@code web.AttentionController}, {@code domain.AttentionService} (module-private, spec #3,
 *       {@code GET /api/v1/attention}) — ships two of the three starter item types the brief
 *       proposed: recent {@code CONSUME_SCHEMA_DENIED} events (itemized, windowed, capped) and a
 *       verify-failure-rate alert (current window vs. the immediately preceding one, multiplier +
 *       minimum-volume thresholds, all {@code khatm.stats.attention.*} config, spec D6). The third
 *       ("signing key approaching rotation") is deliberately descoped this session — it would need
 *       a new {@code key :: api} surface, which was declined (spec D5) to keep {@code key}'s "other
 *       modules must never see rotation" stance untouched; see {@code docs/STATE.md}.
 *   <li>{@code web.ConsumingPartyStatsController}, {@code domain.ConsumingPartyStatsService}
 *       (module-private, {@code GET /api/v1/stats/consuming-parties}) — call volume + success rate
 *       per consuming party for a window, sharing the exact {@code actor_id -> consuming_party}
 *       resolution (spec D2/D4) {@code ActivityService} already solves; multiple {@code api_key}
 *       rows owned by the same party are summed into one entry.
 * </ul>
 *
 * <p>The fourth endpoint ({@code GET /api/v1/admin/signing-keys}) lives in {@code key.web} instead
 * (single-module concern, no composition needed); the daily-breakdown endpoint ({@code GET
 * /api/v1/stats/daily}) stays in {@code shared.web} alongside the existing stats endpoint
 * (audit-only, same reasoning). See {@code docs/specs/FS-1.5.4-dashboard-stats-v2.md}.
 *
 * <p><b>Published events:</b> {@code CredentialIssued}, {@code CredentialConsumed}, {@code
 * CredentialRevoked} (future — KH-1.3)
 *
 * <p><b>Tables owned:</b> {@code credential}, {@code consumption_event}, {@code claim_code}
 *
 * <p><b>Claim delivery (spec FS-1.2.1):</b> {@code ClaimRedemptionService#redeem} is the on-claim
 * half of the {@code disclosures_enc} zeroing contract FS-0.2 §3.7 opened — the other half,
 * expiry-zeroing, is {@code ClaimCodeExpiryWorker} (ADR-09). Together they close that blocker
 * permanently: every {@code disclosures_enc} row ends up {@code NULL} either the moment a wallet
 * claims it or the moment it expires unclaimed, never later, never both, never neither. {@code
 * credential.web.ClaimController}'s {@code POST /api/v1/claims/redeem} authenticates by possession
 * of the claim code alone (spec §9) — {@code rbac.security.SecurityConfig}'s third public endpoint,
 * guarded instead by {@code ClaimRedeemThrottleService}'s per-IP fixed window (D6).
 *
 * <p><b>Consumption lifecycle visibility (KH-1.6-BE, spec FS-1.6):</b> {@code
 * domain.CredentialStatus} (new, module-private) derives an explicit {@code ACTIVE}/{@code
 * EXHAUSTED}/{@code REVOKED}/{@code SUSPENDED}/{@code EXPIRED} status at read time from existing
 * columns — no migration. {@code AtomicConsumptionRecorder#tryConsume} reuses {@code status ::
 * api}'s {@link sy.khatm.platform.status.api.StatusListRevoker#revoke} — the exact bit-flip path
 * {@code CredentialService#revoke} already uses — to flip an exhausted credential's status-list bit
 * exactly once, the moment {@code consumeOne} brings {@code usesRemaining} to {@code 0}. New public
 * endpoint {@code POST /api/v1/credentials/holder-status} (proof-of-possession: bare compact SD-JWT
 * in, {status, maxUses, usesRemaining, lastConsumedAt} out) is a deliberate, explicit reversal of
 * PR #33's "no live uses-remaining channel" stance (spec FS-1.6 §2 V1) — see {@code docs/STATE.md}.
 *
 * <p><b>Cross-module dependencies:</b> {@code key :: api} ({@link
 * sy.khatm.platform.key.api.KeySigner} for signing, {@link sy.khatm.platform.key.api.KeyVerifier}
 * for strict-by-{@code kid} verification, no fallback); {@code schema :: api}, {@code holder ::
 * api}, {@code status :: api}, {@code consumer :: api} — issuing/consuming a credential must
 * resolve the schema, holder, status-list allocation, and consuming party its foreign keys point at
 * (KH-0.2.1 baseline schema, spec FS-0.2 §3.6/§3.9; claim-code redemption resolves the schema too,
 * for the delivered {@code ClaimSchemaRef} display shape); {@code rbac :: api} ({@link
 * sy.khatm.platform.rbac.api.CurrentActorResolver}, KH-1.4.3 — {@code
 * CredentialService#enforceSchemaAllowlist} resolves the authenticated actor to enforce {@code
 * consuming_party_schema} scoping, called by the controller ahead of {@code #consume}; {@link
 * sy.khatm.platform.rbac.api.ApiKeyOwnerLookup}, KH-1.1.5-BE — {@code ActivityService}/{@code
 * ConsumingPartyStatsService} batch-resolve a historical {@code audit_log.actor_id} to its owning
 * consuming party); {@code shared} (its open root package — {@link
 * sy.khatm.platform.shared.TenantContext}, {@link sy.khatm.platform.shared.Uuidv7}); {@code shared
 * :: error} (spec FS-0.6a — {@code KhatmException} subtypes to throw, {@code VerifyReason} for
 * {@code CredentialService#verify}'s domain results); {@code shared :: web} (spec FS-0.6a — {@code
 * ErrorEnvelope}, referenced only from this module's OpenAPI error-response annotations); {@code
 * shared :: audit} (spec FS-0.6b — {@code AuditService}; {@code CredentialService} records {@code
 * CREDENTIAL_ISSUED}/{@code CREDENTIAL_CONSUMED}/{@code CREDENTIAL_REVOKED}/{@code
 * CONSUME_SCHEMA_DENIED}, {@code ClaimRedemptionService}/{@code ClaimRedeemThrottleService} record
 * {@code CLAIM_CODE_REDEEMED}/{@code CLAIM_REDEEM_THROTTLED}, {@code BulkIssuanceService} records
 * one {@code CREDENTIALS_BULK_ISSUED} row per batch (KH-1.1.3), and {@code
 * credential.web.CredentialController#verify} records {@code CREDENTIAL_VERIFY_OK}/{@code
 * CREDENTIAL_VERIFY_FAILED} per call, deliberately outside {@code CredentialService#verify}'s own
 * {@code readOnly = true} transaction).
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
      "key :: api",
      "schema :: api",
      "holder :: api",
      "status :: api",
      "consumer :: api",
      "rbac :: api",
      "tenant :: api",
      "shared",
      "shared :: error",
      "shared :: web",
      "shared :: audit"
    })
package sy.khatm.platform.credential;
