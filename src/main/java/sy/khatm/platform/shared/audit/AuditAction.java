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
  STATUS_LIST_PUBLISHED
}
