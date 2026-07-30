package sy.khatm.platform.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Registry of every API error code the platform can return (CLAUDE.md work rule 3).
 *
 * <p>Format: {@code KH-<MOD>-<NNNN>}. Per spec FS-0.6a D3, the last three digits of {@code NNNN}
 * mirror the HTTP status; the leading digit is a per-module-per-status sequence number, starting at
 * {@code 0} (so a second 404 in the {@code CRD} module would be {@code KH-CRD-1404}, never a
 * renumbering of the first). Module tags are the CONVENTIONS.md §2 set: {@code TEN, KEY, SCH, CRD,
 * STS, LDG, HLD, CNS, RBC, CON, SYS}.
 *
 * <p><b>First batch</b> (spec FS-0.6a §3): codes for request-error paths that exist and are
 * exercised <em>today</em>. Deliberately omitted: a credential-conflict code (the atomic-consume
 * path already returns its outcome as a 200 domain result, not an error). New codes are appended
 * here as new request-error paths are actually built — never renumbered, never added speculatively
 * ahead of the path that needs them.
 *
 * <p><b>{@code SCH} code</b> (KH-1.6-early): {@code GET /api/v1/schemas/{id}} is the first schema
 * lookup that can actually fail — every prior {@code SchemaCatalog} caller either finds-or-creates
 * or degrades gracefully, so no schema-not-found code existed until this endpoint needed one.
 *
 * <p><b>{@code RBC} batch</b> (spec FS-0.6b §5): the three outcomes {@code
 * AuthenticationException}/{@code AuthorizationException} actually throw once session/API-key auth
 * exists — no session/key at all, an invalid/revoked/malformed API key specifically (a materially
 * different situation worth its own code and message — spec FS-0.6b §5), and a valid session/key
 * missing the required scope.
 *
 * <p><b>{@code CLM} batch</b> (spec FS-1.2.1 D5/D6): a new module tag, deliberately distinct from
 * {@code CRD} even though {@code claim_code} is owned by the same {@code credential} Java module
 * (no new Modulith module — the task's hard constraint) — claim-delivery is a conceptually separate
 * bounded concern (wallet-facing, authenticates by code possession, its own generic-failure and
 * rate-limit vocabulary) from credential issuance/verification/consumption, and the spec names
 * these exact codes explicitly. {@link #KH_CLM_0404} is deliberately generic (D5): unknown,
 * expired, already-claimed, and expiry-zeroed all collapse to the same code and message — an
 * anti-probing measure (distinguishing them would leak information to an external scanner); the
 * real reason lives only in {@code audit_log} for the throttle case ({@link #KH_CLM_0429}), never
 * for a plain not-found (D7 — logging every failed guess individually would turn an external
 * scanner into a write amplifier against the audit table).
 *
 * <p><b>{@code KH_CRD_0409}</b> (KH-1.2.2, spec FS-1.2.1 D2's re-issue recovery path exposed over
 * HTTP): the first {@code CRD} code with a status other than 404 — every prior {@code
 * CredentialService} caller either found-or-404'd or returned a 200 domain result, so no
 * state-conflict code existed until minting a claim code needed to reject a revoked/expired
 * credential explicitly.
 *
 * <p><b>{@code KH_STS_0404}</b> (KH-1.3, spec FS-1.3 D2): {@code GET /sl/{tenantSlug}/{listCode}}
 * is the first {@code status} lookup exposed over HTTP — every prior {@code
 * StatusListAllocator}/{@code StatusListRevoker}/{@code StatusListLookup} caller resolves by an
 * internal FK id that always exists, so no not-found code was needed until an external caller could
 * name an arbitrary, possibly-wrong {@code listCode}.
 *
 * <p><b>{@code KH_CNS_0403}</b> (KH-1.4.3, SEC §7): the first {@code CNS} (consumer module) code —
 * a valid {@code CONSUMING_PARTY} API key attempted {@code POST /api/v1/credentials/consume}
 * against a credential whose schema is not in its {@code consuming_party_schema} allowlist.
 * Deliberately its own code rather than reusing {@link #KH_RBC_0403}: "authenticated but this
 * schema isn't yours" is a materially different, support-relevant situation from a generic
 * missing-scope 403.
 *
 * <p><b>{@code USR} batch</b> (KH-2.2b, spec FS-2.2 D5/D6/D8): a new module tag for the tenant
 * user-management surface. {@code USR} is the second tag (after {@code CLM}) that names a bounded
 * concern rather than its owning Java module 1:1 — user management lives inside the {@code rbac}
 * module (no new Modulith module for one surface), but its failure vocabulary ({@code KH-USR-0400}
 * validation, {@code KH-USR-0403} must-change-password, {@code KH-USR-0404} not-found, {@code
 * KH-USR-0409} duplicate-username, {@code KH-USR-0423} last-admin guard) is conceptually separate
 * from {@code RBC}'s auth/forbidden vocabulary, the same separation {@code CLM} made from {@code
 * CRD}. {@code KH_USR_0423} is the first code whose suffix ({@code 423}) does not mirror its HTTP
 * status ({@code 409}) — see its own Javadoc.
 *
 * <p><b>{@code KEY} batch, second wave</b> (KH-2.3a, spec FS-2.3 D2/D4): {@code KEY} already
 * existed ({@link #KH_KEY_0500}) but had no request-error paths until this session's
 * admin-triggered rotation/retirement endpoints — {@code KH-KEY-0404} unknown {@code kid}, {@code
 * KH-KEY-0409} the target key is not {@code RETIRING}, {@code KH-KEY-0422} the min-retiring-age
 * guard (spec V4).
 *
 * <p><b>{@code USR} batch, third wave</b> (KH-2.2c, spec FS-2.2 V1): TOTP second-factor
 * enrollment/login-challenge — {@code KH-USR-1403} the mandatory-enrollment wall (a second {@code
 * 403} in {@code USR}, after {@link #KH_USR_0403}), {@code KH-USR-1409} a TOTP state conflict
 * (enroll/confirm called out of turn). Wrong-code/wrong-recovery-code failures at the login
 * challenge itself deliberately reuse {@link #KH_RBC_0401} — the same D7 anti-enumeration
 * collapsing every other login failure reason already gets, not a new code.
 *
 * <p>{@code docs/error-codes.md} is generated from this enum by a test ({@code
 * ErrorCodesDocGenerationTest}) — never hand-edited (CLAUDE.md work rule 1).
 */
public enum ErrorCode {

  /** A requested credential does not exist. */
  KH_CRD_0404(HttpStatus.NOT_FOUND, "credential.not-found"),

  /**
   * The credential exists but is revoked or past its validity window, so it can no longer accept a
   * new claim code (spec FS-1.2.1 D2's re-issue recovery path, exposed as KH-1.2.2's {@code POST
   * /api/v1/credentials/{id}/claim-code}). One code for both flavors — the mint caller is always an
   * authenticated issuer, not an external prober, so there is no anti-probing reason to collapse
   * this with {@link #KH_CRD_0404} the way {@link #KH_CLM_0404} collapses its own set.
   */
  KH_CRD_0409(HttpStatus.CONFLICT, "credential.not-claimable"),

  /** Signing a credential's SD-JWT failed (spec FS-0.5's {@code KeySigner}, wrapped). */
  KH_KEY_0500(HttpStatus.INTERNAL_SERVER_ERROR, "key.signing-failed"),

  /**
   * {@code POST /api/v1/admin/signing-keys/{kid}/retire} (KH-2.3a, spec FS-2.3 D4) named a {@code
   * kid} that does not exist for the current tenant.
   */
  KH_KEY_0404(HttpStatus.NOT_FOUND, "key.not-found"),

  /**
   * {@code POST /api/v1/admin/signing-keys/{kid}/retire} (KH-2.3a, spec FS-2.3 D4) named a key that
   * is not currently {@code RETIRING} — only a {@code RETIRING} key can be retired ({@code ACTIVE}
   * must be rotated out first; {@code RETIRED} is already retired; {@code PENDING} is never
   * reachable today).
   */
  KH_KEY_0409(HttpStatus.CONFLICT, "key.not-retiring"),

  /**
   * {@code POST /api/v1/admin/signing-keys/{kid}/retire} (KH-2.3a, spec FS-2.3 D4/V4) was called on
   * a {@code RETIRING} key that has not yet reached {@code khatm.keys.min-retiring-age} (default
   * {@code P30D}) — the guard against retiring a key still needed to verify long-lived offline
   * documents. {@code details[]} carries the remaining wait duration. {@code force=true} bypasses
   * this (audited, {@code detail.forced=true} on the {@link
   * sy.khatm.platform.shared.audit.AuditAction#KEY_RETIRED} row) — the code's suffix mirrors HTTP
   * 422 Unprocessable Entity, the same divergence-from-HTTP-status precedent {@link #KH_USR_0423}
   * already set (there, the suffix is thematic and the wire status is 409; here, the suffix
   * genuinely is the wire status).
   */
  KH_KEY_0422(HttpStatus.UNPROCESSABLE_ENTITY, "key.retiring-too-young"),

  /** Bean Validation rejected the request body; see the envelope's {@code details[]}. */
  KH_SYS_0400(HttpStatus.BAD_REQUEST, "validation.failed"),

  /**
   * Fallback for any exception not otherwise mapped — the {@code GlobalExceptionHandler} catch-all.
   * Never carries internal detail to the client (CLAUDE.md work rule 3).
   */
  KH_SYS_0500(HttpStatus.INTERNAL_SERVER_ERROR, "system.unexpected-error"),

  /**
   * No session and no API key on a protected path, or a console login failure of any kind — spec
   * FS-0.6b D7 mandates the same generic message for every login failure reason (unknown user, bad
   * password, temporary lockout, administrative LOCKED/DISABLED); the real reason lives only in the
   * {@code audit_log} row, never in this response.
   */
  KH_RBC_0401(HttpStatus.UNAUTHORIZED, "error.rbc.unauthenticated"),

  /**
   * An {@code Authorization: Bearer khk_...} header was presented but is malformed, unknown, or
   * revoked — distinct from {@link #KH_RBC_0401} (spec FS-0.6b §5) because a caller who attempted a
   * specific key is in a materially different situation from one presenting no credentials at all.
   */
  KH_RBC_1401(HttpStatus.UNAUTHORIZED, "error.rbc.api_key_invalid"),

  /** A session or API key is valid but lacks the scope the endpoint requires. */
  KH_RBC_0403(HttpStatus.FORBIDDEN, "error.rbc.forbidden"),

  /** A requested credential schema does not exist. */
  KH_SCH_0404(HttpStatus.NOT_FOUND, "schema.not-found"),

  /**
   * A claim code is unknown, malformed, expired, already claimed, or expiry-zeroed — one generic
   * outcome for every flavor (spec FS-1.2.1 D5), so an external caller cannot distinguish "never
   * existed" from "someone already claimed it."
   */
  KH_CLM_0404(HttpStatus.NOT_FOUND, "error.clm.invalid_or_expired"),

  /**
   * The per-IP claim-redeem throttle tripped (spec FS-1.2.1 D6) — too many {@code POST
   * /api/v1/claims/redeem} attempts from the same address within the fixed window.
   */
  KH_CLM_0429(HttpStatus.TOO_MANY_REQUESTS, "error.clm.throttled"),

  /** A requested status list ({@code tenantSlug}/{@code listCode}) does not exist. */
  KH_STS_0404(HttpStatus.NOT_FOUND, "status.not-found"),

  /**
   * A {@code CONSUMING_PARTY} API key attempted to consume a credential whose schema is not in its
   * {@code consuming_party_schema} allowlist (spec SEC §7, KH-1.4.3, deny-by-default).
   */
  KH_CNS_0403(HttpStatus.FORBIDDEN, "consumer.schema-not-allowed"),

  /**
   * A consuming-party admin request (KH-1.4.4, {@code POST /api/v1/admin/consuming-parties})
   * carried a {@code code} that is not a valid lowercase slug ({@code ^[a-z0-9][a-z0-9-_]{1,62}$}).
   * The offending value is not echoed back — this is a plain format rejection.
   */
  KH_CNS_0400(HttpStatus.BAD_REQUEST, "consumer.invalid-code"),

  /**
   * A requested consuming party does not exist in the current tenant (KH-1.4.4 — the admin
   * suspend/activate/allow/mint operations, and the allow endpoint's party check). The first {@code
   * CNS} 404: before the admin plane, a consuming party was only ever resolved by {@code
   * ConsumingPartyRegistry#ensure}, which creates one if absent, so no not-found path existed.
   */
  KH_CNS_0404(HttpStatus.NOT_FOUND, "consumer.party-not-found"),

  /**
   * A schema named in a consuming-party allowlist request (KH-1.4.4 D5, {@code POST
   * /api/v1/admin/consuming-parties/{id}/allowed-schemas}) does not exist in the current tenant. A
   * second {@code CNS} 404, distinct from {@link #KH_CNS_0404} (which is about the party): the
   * caller needs to know <em>which</em> of the two referenced entities was missing.
   */
  KH_CNS_1404(HttpStatus.NOT_FOUND, "consumer.allowlist-schema-not-found"),

  /**
   * Explicit admin creation of a consuming party (KH-1.4.4 D2) whose {@code code} is already
   * registered in this tenant. Creation is idempotent by identity (the id is derived from {@code
   * (tenant, code)}), so a duplicate cannot produce a second row — but a second explicit create is
   * reported as a conflict rather than silently overwriting the existing party's name/status.
   */
  KH_CNS_0409(HttpStatus.CONFLICT, "consumer.duplicate-code"),

  /**
   * A {@code POST}/{@code PUT} schema-authoring request (KH-1.1.1, {@code schema.web
   * .SchemaController}'s create/update/version endpoints) failed a business-level check Bean
   * Validation alone cannot express — an unsupported claim field type, a {@code label_i18n}/{@code
   * nameI18n} missing {@code en} or {@code ar}, a duplicate claim field name, or an {@code
   * sdFields} entry not among the submitted claim field names. One code for every flavor (the
   * offending reason is always substituted into the message via {@code {0}}), the same collapsing
   * judgment call {@link #KH_CLM_0404} already made for a different reason (there, anti-probing;
   * here, simply that these are all "the request body doesn't describe a valid schema" and don't
   * warrant a code each).
   */
  KH_SCH_0400(HttpStatus.BAD_REQUEST, "schema.validation-failed"),

  /**
   * {@code PUT /api/v1/schemas/{id}} was called on a schema that is not {@code DRAFT} — publishing
   * is KH-1.1.1's immutability line: a {@code PUBLISHED} schema's authoring fields can never be
   * mutated in place, only superseded by a new version ({@code POST
   * /api/v1/schemas/{id}/versions}).
   */
  KH_SCH_0409(HttpStatus.CONFLICT, "schema.immutable-after-publish"),

  /**
   * A schema lifecycle action was attempted from a status that does not allow it: publishing a
   * schema that is not {@code DRAFT}, archiving one that is not {@code PUBLISHED}, versioning one
   * that is not {@code PUBLISHED}, or — the one case with no dedicated management endpoint of its
   * own — {@code CredentialService#issue} resolving an existing schema (by code/version) that is
   * {@code DRAFT} or {@code ARCHIVED} rather than {@code PUBLISHED}. All four are the same shape of
   * conflict ("this action needs the schema in a different lifecycle state than it's actually in"),
   * so they share one code rather than one each.
   */
  KH_SCH_1409(HttpStatus.CONFLICT, "schema.invalid-transition"),

  /**
   * A {@code POST /api/v1/credentials/bulk} request (KH-1.1.3) failed batch-level validation before
   * any item was processed — an empty {@code items} list or one exceeding the 200-item cap. One
   * code for both flavors (the offending reason is substituted into the message via {@code {0}}),
   * the same collapsing judgment call {@link #KH_SCH_0400} already made: both are "the batch
   * request itself is malformed," not a per-item outcome (those are reported inside {@code
   * BulkIssueResponse.results}, never as this code).
   */
  KH_CRD_0400(HttpStatus.BAD_REQUEST, "credential.bulk-validation-failed"),

  /**
   * A tenant admin request (KH-2.1, {@code POST /api/v1/admin/tenants}) carried a {@code slug} that
   * is not a valid lowercase slug (same regex as {@link #KH_CNS_0400}'s consuming-party {@code
   * code}: {@code ^[a-z0-9][a-z0-9-_]{1,62}$}).
   */
  KH_TNT_0400(HttpStatus.BAD_REQUEST, "tenant.invalid-slug"),

  /**
   * A requested tenant does not exist (KH-2.1 — the admin get/suspend/activate operations, and the
   * per-tenant JWKS/status-list public endpoints resolving an unknown {@code tenantSlug}).
   */
  KH_TNT_0404(HttpStatus.NOT_FOUND, "tenant.not-found"),

  /**
   * Explicit admin creation of a tenant (KH-2.1 D6) whose {@code slug} already belongs to a
   * fully-onboarded tenant (one with an {@code ACTIVE} signing key already). A slug that exists but
   * has no key yet (a prior onboarding attempt died mid-way, spec V3) resumes instead of
   * conflicting — see {@code tenant.api.TenantAdmin#create}'s Javadoc.
   */
  KH_TNT_0409(HttpStatus.CONFLICT, "tenant.duplicate-slug"),

  /**
   * A tenant user-management request (KH-2.2b, spec FS-2.2 D5, {@code
   * rbac.web.UserAdminController}) failed a business-level check Bean Validation cannot express —
   * an unknown role code, an empty {@code roles} set, or a username that is not a valid slug. One
   * code for every flavor (the reason is substituted into the message via {@code {0}}), the same
   * collapsing judgment call {@link #KH_SCH_0400} already made.
   */
  KH_USR_0400(HttpStatus.BAD_REQUEST, "user.validation-failed"),

  /**
   * An authenticated user whose {@code must_change_password} flag is set called any endpoint other
   * than the self-service password-change one (KH-2.2b, {@code rbac.security
   * .PasswordChangeEnforcementFilter}). The flag is set on temporary-password provisioning (D5
   * create/reset-password, D6 onboarding's first admin) and cleared on first real password change;
   * while it is set, this 403 with its distinct code is how the console routes to the change screen
   * rather than treating the user as merely lacking a scope ({@link #KH_RBC_0403}).
   */
  KH_USR_0403(HttpStatus.FORBIDDEN, "user.must-change-password"),

  /** A requested tenant user does not exist in the current tenant (KH-2.2b, spec FS-2.2 D5). */
  KH_USR_0404(HttpStatus.NOT_FOUND, "user.not-found"),

  /**
   * Explicit creation of a tenant user (KH-2.2b, spec FS-2.2 D5) whose {@code username} already
   * exists in this tenant. The unique constraint {@code (tenant_id, username)} makes a second row
   * impossible; a second explicit create is reported as a conflict rather than silently
   * overwriting.
   */
  KH_USR_0409(HttpStatus.CONFLICT, "user.duplicate-username"),

  /**
   * The last-tenant-admin guard (KH-2.2b, spec FS-2.2 D5): a lock, disable, or role-change that
   * would leave the tenant with zero {@code ACTIVE} users holding the {@code tenant:admin} scope.
   * Atomically rejected — race-proofed by a per-tenant advisory lock so two concurrent operations
   * against the final two administrators cannot both succeed ({@code db.ConcurrentLastAdminTest}).
   *
   * <p><b>Code suffix vs. HTTP status — first documented divergence.</b> The code is {@code 0423}
   * (HTTP 423 Locked is thematically exact: the operation would lock the tenant out of its own
   * administration), but the wire status is {@code 409 Conflict}, per the approved brief's explicit
   * {@code "409 KH-USR-0423"}. The "last three digits mirror the HTTP status" rule recorded in this
   * enum's class Javadoc is a mnemonic, not a contract; this is its first exception, recorded here
   * and in {@code docs/error-codes.md} rather than silently breaking the pattern.
   */
  KH_USR_0423(HttpStatus.CONFLICT, "user.last-admin"),

  /**
   * An authenticated user holding a mandatory-2FA scope ({@code revoke}/{@code tenant:admin}/
   * {@code platform:admin}/{@code key:manage}, spec FS-2.2 V1) with no active TOTP enrollment
   * called any endpoint other than enroll/confirm ({@code rbac.security
   * .TotpEnrollmentEnforcementFilter}). A second {@code 403} in {@code USR}, after {@link
   * #KH_USR_0403} — distinct so the console can route to TOTP enrollment specifically rather than a
   * generic missing-scope 403 or the unrelated forced-password-change screen.
   */
  KH_USR_1403(HttpStatus.FORBIDDEN, "user.totp-required"),

  /**
   * A TOTP enrollment/confirmation request conflicted with the user's current TOTP state (spec
   * FS-2.2 V1, {@code rbac.domain.TotpService}): enrolling while already active, confirming with no
   * pending enrollment, or confirming a pending enrollment that expired ({@code
   * khatm.auth.totp.enroll-ttl}). One code for every flavor (the reason is substituted into the
   * message via {@code {0}}), the same collapsing judgment call {@link #KH_SCH_0400} already made.
   */
  KH_USR_1409(HttpStatus.CONFLICT, "user.totp-conflict");

  private final HttpStatus httpStatus;
  private final String messageKey;

  ErrorCode(HttpStatus httpStatus, String messageKey) {
    this.httpStatus = httpStatus;
    this.messageKey = messageKey;
  }

  /**
   * The HTTP status the {@code GlobalExceptionHandler} responds with for this code.
   *
   * @return the HTTP status
   */
  public HttpStatus httpStatus() {
    return httpStatus;
  }

  /**
   * The {@code MessageSource} key this code's client-facing {@code message} resolves from.
   *
   * @return the dot-notation message key
   */
  public String messageKey() {
    return messageKey;
  }

  /**
   * The wire-format code string, e.g. {@code KH-CRD-0404} (enum constant names use underscores;
   * Java identifiers cannot contain hyphens).
   *
   * @return the hyphenated code string
   */
  public String code() {
    return name().replace('_', '-');
  }
}
