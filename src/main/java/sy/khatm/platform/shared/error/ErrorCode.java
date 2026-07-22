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
  KH_CRD_0400(HttpStatus.BAD_REQUEST, "credential.bulk-validation-failed");

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
