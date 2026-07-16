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
 * <p><b>This is the first batch only</b> (spec FS-0.6a §3): codes for request-error paths that
 * exist and are exercised <em>today</em>. Deliberately omitted: a schema-not-found code (nothing in
 * the codebase currently looks up a schema in a way that can fail — {@code SchemaCatalog} methods
 * find-or-create or degrade gracefully), a credential-conflict code (the atomic-consume path
 * already returns its outcome as a 200 domain result, not an error), and any {@code RBC} codes
 * (KH-0.6b). New codes are appended here as new request-error paths are actually built — never
 * renumbered, never added speculatively ahead of the path that needs them.
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
  KH_SYS_0500(HttpStatus.INTERNAL_SERVER_ERROR, "system.unexpected-error");

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
