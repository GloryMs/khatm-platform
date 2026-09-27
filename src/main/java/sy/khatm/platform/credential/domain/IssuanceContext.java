package sy.khatm.platform.credential.domain;

/**
 * Request-scoped issuance inputs that are not part of the request body (KH-2.8.2). The web layer
 * fills it from HTTP headers so the domain never touches {@code HttpServletRequest}.
 *
 * @param idempotencyKey the raw {@code Idempotency-Key} header, or {@code null} when absent. Never
 *     logged or audited as-is — only its SHA-256 (spec FS-2.7a D11)
 */
public record IssuanceContext(String idempotencyKey) {

  /** No headers at all: seeders, internal callers, and every pre-KH-2.8.2 code path. */
  public static final IssuanceContext NONE = new IssuanceContext(null);
}
