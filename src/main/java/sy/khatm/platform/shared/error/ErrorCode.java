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
 * <p>{@code docs/error-codes.md} is generated from this enum by a test ({@code
 * ErrorCodesDocGenerationTest}) — never hand-edited (CLAUDE.md work rule 1).
 */
public enum ErrorCode {

  /** A requested credential does not exist. */
  KH_CRD_0404(HttpStatus.NOT_FOUND, "credential.not-found"),

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
  KH_SCH_0404(HttpStatus.NOT_FOUND, "schema.not-found");

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
