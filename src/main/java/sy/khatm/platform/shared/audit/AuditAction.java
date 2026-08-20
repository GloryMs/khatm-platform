package sy.khatm.platform.shared.audit;

/**
 * The catalog of events the platform records to {@code audit_log} (spec FS-0.6b §6, SEC §9.4).
 *
 * <p>Every business-significant state change goes through {@link AuditService#record} with one of
 * these — never a raw string, so the set of possible {@code action} values is closed and
 * discoverable from this enum alone. {@link #name()} is the exact value stored in {@code
 * audit_log.action}.
 */
public enum AuditAction {

  /**
   * A credential was issued ({@code credential} module). {@code entityRef} is the credential ref.
   */
  CREDENTIAL_ISSUED,

  /**
   * A credential was consumed ({@code credential} module). {@code entityRef} is the credential id.
   */
  CREDENTIAL_CONSUMED,

  /**
   * A credential was revoked ({@code credential} module). {@code entityRef} is the credential id.
   */
  CREDENTIAL_REVOKED,

  /**
   * A credential's last remaining use was consumed, transitioning it to {@code EXHAUSTED} ({@code
   * credential} module, KH-1.6, spec FS-1.6 D1). {@code entityRef} is the credential id. Recorded
   * exactly once per credential, in the same transaction as the consuming {@link
   * #CREDENTIAL_CONSUMED} row and the status-list bit flip that mirrors {@link #CREDENTIAL_REVOKED}
   * D3's revoke path.
   */
  CREDENTIAL_EXHAUSTED,

  /**
   * A new issuer signing key was created ({@code key} module, KH-0.5). {@code entityRef} is the
   * kid.
   */
  KEY_CREATED,

  /**
   * An issuer signing key was rotated ({@code key} module, KH-0.5, admin-triggered as of KH-2.3a).
   * {@code entityRef} is the new kid.
   */
  KEY_ROTATED,

  /**
   * An issuer signing key transitioned {@code RETIRING} → {@code RETIRED} ({@code key} module,
   * KH-2.3a, {@code POST /api/v1/admin/signing-keys/{kid}/retire}, spec FS-2.3 D4). {@code
   * entityRef} is the kid; {@code detail.forced} is {@code true} when the {@code
   * khatm.keys.min-retiring-age} guard was bypassed via {@code force=true}, {@code false} when the
   * key had already aged past it naturally.
   */
  KEY_RETIRED,

  /**
   * A retire attempt was rejected by the {@code khatm.keys.min-retiring-age} guard ({@code key}
   * module, KH-2.4x, closing debt A7 found by GAMEDAY KH-2.3.3 / QS-A7-GITCHECK: this rejection
   * branch used to be silent-by-construction — the {@code KH-KEY-0422} throw happened strictly
   * before any audit write). {@code entityRef} is the kid; {@code detail} carries {@code elapsed}
   * and {@code minRetiringAge} (both {@link java.time.Duration#toString() ISO-8601 duration
   * strings}) — never {@code forced}, since this branch is reached only when {@code force} is
   * {@code false}. Recorded via {@link AuditService#recordIndependently}, not {@link
   * AuditService#record} — the enclosing {@code retire()} call is about to throw and roll back, so
   * this row must commit in its own, separate physical transaction to survive.
   */
  KEY_RETIRE_REJECTED,

  /**
   * The claim-code expiry sweep zeroed one or more codes (ADR-09-worker). Actor is always SYSTEM.
   */
  CLAIM_CODES_EXPIRED,

  /** A console login succeeded ({@code rbac} module). */
  AUTH_LOGIN_SUCCESS,

  /**
   * A console login attempt failed for any reason (unknown user, bad password, administrative
   * {@code LOCKED}/{@code DISABLED}) — the real reason lives in {@code detail} only, never in the
   * generic client-facing message (spec FS-0.6b D7).
   */
  AUTH_LOGIN_FAILED,

  /**
   * A login attempt was rejected specifically because the temporary Redis lockout counter tripped
   * (spec FS-0.6b D6) — distinct from {@link #AUTH_LOGIN_FAILED} because the password may have been
   * correct on this particular attempt; the lockout state overrides it regardless.
   */
  AUTH_LOCKOUT_TRIGGERED,

  /**
   * An {@code Authorization: Bearer khk_...} header failed to authenticate. {@code detail.prefix}
   * only — never the secret.
   */
  API_KEY_AUTH_FAILED,

  /** An API key was created via the admin endpoint. {@code entityRef} is the key's prefix. */
  API_KEY_CREATED,

  /** An API key was revoked via the admin endpoint. {@code entityRef} is the key's prefix. */
  API_KEY_REVOKED,

  /** A user account was created ({@code AdminBootstrap} or a future admin console). */
  USER_CREATED,

  /**
   * A user's role set was replaced (KH-2.2b, {@code POST /api/v1/users/{id}/roles}, spec FS-2.2
   * D5). {@code entityRef} is the username; {@code detail.roles} carries the new role-code set —
   * never credentials.
   */
  USER_ROLES_CHANGED,

  /**
   * A role grant/replace was rejected by the role-grant ceiling (chore/role-grant-ceiling — {@code
   * rbac.domain.UserAdminService}, the single chokepoint covering both {@code POST
   * /api/v1/users}/{@code POST /api/v1/users/{id}/roles} and the {@code org:admin}-mediated {@code
   * POST /api/v1/org/children/{id}/users}, deny-by-default). {@code entityRef} is the target
   * username; {@code detail} carries {@code roleCode} and the specific offending {@code scope}.
   * Recorded via {@link AuditService#recordIndependently}, not {@link AuditService#record} — the
   * same "the rejection must survive the enclosing rollback" reason as {@link
   * #KEY_RETIRE_REJECTED}.
   */
  ROLE_GRANT_REJECTED,

  /**
   * A user was administratively locked (KH-2.2b, {@code POST /api/v1/users/{id}/lock}, spec FS-2.2
   * D5). {@code entityRef} is the username.
   */
  USER_LOCKED,

  /**
   * A locked user was restored to active (KH-2.2b, {@code POST /api/v1/users/{id}/unlock}, spec
   * FS-2.2 D5). {@code entityRef} is the username.
   */
  USER_UNLOCKED,

  /**
   * A user was administratively disabled (KH-2.2b, {@code POST /api/v1/users/{id}/disable}, spec
   * FS-2.2 D5). {@code entityRef} is the username.
   */
  USER_DISABLED,

  /**
   * A user's password was administratively reset to a new temporary one (KH-2.2b, {@code POST
   * /api/v1/users/{id}/reset-password}, spec FS-2.2 D5) — the temporary password is shown to the
   * caller exactly once (plaintext-once, like an API key) and the {@code must_change_password} flag
   * is set. {@code entityRef} is the username; the one-time password itself is never audited.
   */
  USER_PASSWORD_RESET,

  /**
   * A user set their own real password via the self-service endpoint (KH-2.2b, {@code POST
   * /api/v1/users/me/password}) — the one call allowed while {@code must_change_password} is set,
   * and the call that clears it. {@code entityRef} is the username; the new password is never
   * audited.
   */
  USER_PASSWORD_CHANGED,

  /**
   * A wallet successfully redeemed a claim code ({@code credential} module, KH-1.2.1). {@code
   * entityRef} is the credential's ref — never the code itself. Attributed to the credential's own
   * issuing tenant ({@code ClaimRedemptionService#redeem} always has the row in hand by the time
   * this is recorded) — never the platform default tenant, fixed KH-2.6b-adjacent (spec FS-2.5;
   * same root cause and same fix shape as {@link #CREDENTIAL_VERIFY_OK}'s own note).
   */
  CLAIM_CODE_REDEEMED,

  /**
   * An issuer minted a wallet claim code for an already-issued credential ({@code credential}
   * module, KH-1.2.2, {@code POST /api/v1/credentials/{id}/claim-code}). {@code entityRef} is the
   * credential's ref — never the code itself, and never the code this mint silently voided.
   */
  CLAIM_CODE_ISSUED,

  /**
   * The per-IP claim-redeem throttle tripped (spec FS-1.2.1 D6/D7) — the one failure flavor of
   * {@code POST /api/v1/claims/redeem} that IS recorded individually. {@code detail} carries the
   * source IP and the attempt count; never the code.
   */
  CLAIM_REDEEM_THROTTLED,

  /**
   * A status list's signed bitstring artifact was (re)published ({@code status} module, KH-1.3,
   * spec FS-1.3 D7). Actor is always SYSTEM (published from a worker consumer or sweep, never a
   * request thread). {@code entityRef} is the list's {@code list_code}; {@code detail.version}
   * carries the version just published — never the bitstring or the artifact itself (SEC §9).
   */
  STATUS_LIST_PUBLISHED,

  /**
   * A {@code CONSUMING_PARTY}-authenticated {@code POST /api/v1/credentials/consume} call was
   * denied because the credential's schema is not in the caller's {@code consuming_party_schema}
   * allowlist ({@code consumer} module, KH-1.4.3, deny-by-default). {@code entityRef} is the
   * credential's ref; {@code detail} carries {@code schemaId} and {@code party} — never claims
   * material.
   */
  CONSUME_SCHEMA_DENIED,

  /**
   * A new {@code DRAFT} credential schema was created ({@code schema} module, KH-1.1.1). {@code
   * entityRef} is {@code code:version}; {@code detail} is never the full {@code claims_def} — that
   * belongs to {@code credential_schema} itself, not the audit log.
   */
  SCHEMA_CREATED,

  /**
   * A {@code DRAFT} schema's authoring fields were edited in place ({@code schema} module,
   * KH-1.1.1, {@code PUT /api/v1/schemas/{id}}). {@code entityRef} is {@code code:version}.
   */
  SCHEMA_UPDATED,

  /**
   * A {@code DRAFT} schema was published, becoming immutable and available for issuance ({@code
   * schema} module, KH-1.1.1). {@code entityRef} is {@code code:version}.
   */
  SCHEMA_PUBLISHED,

  /**
   * A new {@code DRAFT} version of a {@code PUBLISHED} schema was created ({@code schema} module,
   * KH-1.1.1, {@code POST /api/v1/schemas/{id}/versions}). {@code entityRef} is the new version's
   * {@code code:version}.
   */
  SCHEMA_VERSION_CREATED,

  /**
   * A {@code PUBLISHED} schema was archived, stopping new issuance against it — existing
   * credentials and their verification/consumption are unaffected ({@code schema} module,
   * KH-1.1.1). {@code entityRef} is {@code code:version}.
   */
  SCHEMA_ARCHIVED,

  /**
   * A consuming party was registered via the admin plane ({@code consumer} module, KH-1.4.4, {@code
   * POST /api/v1/admin/consuming-parties}). {@code entityRef} is the party's {@code code}.
   */
  CONSUMING_PARTY_CREATED,

  /**
   * A consuming party was suspended — its API keys stop authenticating ({@code consumer} module,
   * KH-1.4.4). {@code entityRef} is the party's {@code code}.
   */
  CONSUMING_PARTY_SUSPENDED,

  /**
   * A suspended consuming party was reactivated ({@code consumer} module, KH-1.4.4). {@code
   * entityRef} is the party's {@code code}.
   */
  CONSUMING_PARTY_ACTIVATED,

  /**
   * A schema was added to a consuming party's allowlist ({@code consumer} module, KH-1.4.4). {@code
   * entityRef} is the party's {@code code}; {@code detail.schemaId} identifies the schema.
   */
  CONSUMING_PARTY_SCHEMA_ALLOWED,

  /**
   * A schema was removed from a consuming party's allowlist ({@code consumer} module, KH-1.4.4).
   * {@code entityRef} is the party's {@code code}; {@code detail.schemaId} identifies the schema.
   */
  CONSUMING_PARTY_SCHEMA_DISALLOWED,

  /**
   * A bulk issuance batch completed ({@code credential} module, KH-1.1.3, {@code POST
   * /api/v1/credentials/bulk}) — one row per batch, in addition to the per-item {@link
   * #CREDENTIAL_ISSUED} rows the reused single-issue path already writes. {@code entityRef} is the
   * batch's {@code schemaCode}; {@code detail} carries {@code total}/{@code succeeded}/{@code
   * failed} counts only — never any item's claims.
   */
  CREDENTIALS_BULK_ISSUED,

  /**
   * An online {@code POST /api/v1/credentials/verify} call resolved as valid (KH-1.1.3, spec
   * FS-1.5.3's pilot-metrics commitment). {@code entityRef} is the credential's ref when the
   * presentation's {@code ref} claim resolved to a known row, {@code null} otherwise; {@code
   * detail.reason} carries the {@code VerifyReason} code — never the presented claims themselves.
   * Recorded by {@code credential.web.CredentialController#verify} (its private {@code
   * auditVerify}) after {@code CredentialService#verifyOutcome} returns, deliberately outside that
   * method's own {@code readOnly = true} transaction (a read-only transaction cannot accept this
   * write). Attributed to the credential's own issuing tenant, resolved by {@code verifyOutcome}
   * alongside the row itself — never the platform default tenant unless the presentation never
   * resolved a credential row at all (fixed KH-2.6b-adjacent, spec FS-2.5: every verify audit row
   * had been landing under the default tenant regardless, surfaced by the aggregated report always
   * reading zero verify activity for real, non-default tenants).
   */
  CREDENTIAL_VERIFY_OK,

  /**
   * An online {@code POST /api/v1/credentials/verify} call resolved as invalid (KH-1.1.3) — the
   * counterpart to {@link #CREDENTIAL_VERIFY_OK}, same {@code entityRef}/{@code detail}/tenant-
   * attribution shape (a credential row still resolved on most failure reasons — revoked,
   * exhausted, a tampered disclosure — just not on the early-exit ones with nothing to attribute
   * to: malformed, bad signature, unknown {@code kid}/{@code ref}).
   */
  CREDENTIAL_VERIFY_FAILED,

  /**
   * A tenant was onboarded via the admin plane (KH-2.1, {@code tenant} module, {@code POST
   * /api/v1/admin/tenants}). {@code entityRef} is the tenant's slug. Recorded once per genuinely
   * new row — a resumed partial onboarding (spec V3) does not re-record this.
   */
  TENANT_CREATED,

  /**
   * A tenant was suspended — its own users'/API keys' authentication stops entirely, though
   * already-issued credentials keep verifying/consuming and its JWKS/status-list stay public
   * (KH-2.1 D7/V4). {@code entityRef} is the tenant's slug.
   */
  TENANT_SUSPENDED,

  /** A suspended tenant was reactivated (KH-2.1). {@code entityRef} is the tenant's slug. */
  TENANT_ACTIVATED,

  /**
   * A {@code platform:admin} caller acted on a tenant other than their own ({@code shared
   * .OnBehalfOfExecutor}, KH-2.2a, spec FS-2.2 D4) — e.g. provisioning a newly onboarded tenant's
   * first operational key. Recorded under the <em>caller's own</em> ambient tenant (the platform
   * admin's own audit trail), before {@code TenantContext} is switched to the target — {@code
   * entityRef} is the target tenant's slug, identifying which tenant was acted upon.
   */
  ON_BEHALF_OF,

  /**
   * A TOTP enrollment was confirmed/activated (KH-2.2c, spec FS-2.2 V1, {@code POST
   * /api/v1/users/me/totp/confirm}). {@code entityRef} is the username.
   */
  USER_TOTP_ENROLLED,

  /**
   * A user's TOTP enrollment was administratively cleared (KH-2.2c, spec FS-2.2 V1, {@code POST
   * /api/v1/users/{id}/totp/reset} or its on-behalf-of variant) — they re-enroll at next login if a
   * mandatory scope requires it. {@code entityRef} is the username; {@code detail.hadActive}
   * records whether there was actually anything to clear (idempotent no-op vs. a real reset).
   */
  USER_TOTP_RESET,

  /**
   * A one-time TOTP recovery code was consumed to complete a login (KH-2.2c, spec FS-2.2 V1).
   * {@code entityRef} is the username; {@code detail.remaining} is how many recovery codes are left
   * — never the code itself, used or otherwise.
   */
  USER_TOTP_RECOVERY_CODE_USED,

  /**
   * A human operator attested a scanned document at issuance time, against a schema with {@code
   * requires_attestation=true} ({@code credential} module, KH-2.4, spec FS-2.4 item 2). {@code
   * entityRef} is the issued credential's {@code ref}; {@code detail.note} carries the operator's
   * optional note — never the document itself or any claim value (P1/SEC §9). Recorded in the same
   * transaction as, and ordered strictly before, the {@link #CREDENTIAL_ISSUED} row for the same
   * credential.
   */
  SCAN_ATTESTED,

  /**
   * A tenant was linked to a parent tenant, or re-linked to a different one (KH-2.6a, {@code
   * tenant} module, spec FS-2.5 §2, {@code POST /api/v1/admin/tenants/{id}/parent}). {@code
   * entityRef} is the child tenant's slug; {@code detail.parentSlug} carries the new parent's slug
   * — administrative metadata only (spec FS-2.5 §1), never a security-relevant change.
   */
  TENANT_PARENT_LINKED,

  /**
   * A tenant's parent link was cleared, making it a root again (KH-2.6a, {@code tenant} module,
   * spec FS-2.5 §2, {@code POST /api/v1/admin/tenants/{id}/parent} with a {@code null} {@code
   * parentSlug}). {@code entityRef} is the tenant's slug; {@code detail.previousParentSlug} carries
   * the parent it was unlinked from.
   */
  TENANT_PARENT_UNLINKED,

  /**
   * An {@code org:admin} caller acted on one of their tenant's <em>direct</em> children ({@code
   * shared.OnBehalfOfExecutor#runAsChildOrg}, KH-2.6b, spec FS-2.5 §3) — e.g. creating a user in
   * that child, or suspending it. Recorded under the <em>caller's own</em> ambient (parent) tenant,
   * before {@code TenantContext} is switched to the child — the exact {@link #ON_BEHALF_OF} shape,
   * kept as a distinct action so the materially narrower org-plane (direct children only, entity
   * management not content) is distinguishable in the audit trail from {@link #ON_BEHALF_OF}'s
   * platform-wide reach. {@code entityRef} is the target child's slug. The matching row in the
   * child's own audit trail (spec §3's "dual audit") is whatever specific action the org-mediated
   * call itself performs there (e.g. {@link #USER_CREATED}, {@link #TENANT_SUSPENDED}) — those
   * actions already self-audit under whichever tenant is ambient when they run, so no separate
   * child-side marker action is needed for a mutating call; a read-only org call (listing a child's
   * users, viewing its schemas) has no child-side row, matching the platform-wide convention that
   * reads are not audited.
   */
  ORG_ON_BEHALF_OF,

  /**
   * An {@code org:admin} caller fetched the aggregated proofs-not-content report over their
   * tenant's full descendant subtree ({@code rbac.web.OrgAdminController}, KH-2.6b, spec FS-2.5
   * §4). {@code entityRef} is {@code null} (the report spans the whole subtree, not one entity);
   * {@code detail} carries {@code descendantCount}, {@code from}, and {@code to} — counters and a
   * window, never any row-level detail (P1).
   */
  ORG_REPORT_VIEWED
}
