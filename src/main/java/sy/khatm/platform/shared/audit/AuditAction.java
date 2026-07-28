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
   * An issuer signing key was rotated ({@code key} module, KH-0.5). {@code entityRef} is the new
   * kid.
   */
  KEY_ROTATED,

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
   * A wallet successfully redeemed a claim code ({@code credential} module, KH-1.2.1). {@code
   * entityRef} is the credential's ref — never the code itself.
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
   * Recorded by {@code credential.web.CredentialController#verify} after {@code
   * CredentialService#verify} returns, deliberately outside that method's own {@code readOnly =
   * true} transaction (a read-only transaction cannot accept this write).
   */
  CREDENTIAL_VERIFY_OK,

  /**
   * An online {@code POST /api/v1/credentials/verify} call resolved as invalid (KH-1.1.3) — the
   * counterpart to {@link #CREDENTIAL_VERIFY_OK}, same {@code entityRef}/{@code detail} shape.
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
  TENANT_ACTIVATED
}
