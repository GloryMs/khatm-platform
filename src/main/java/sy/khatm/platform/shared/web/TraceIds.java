package sy.khatm.platform.shared.web;

/**
 * The shared MDC key and header name used to correlate a request across its response, the error
 * envelope, and log lines (spec FS-0.6a D6) — one constant, used by both {@link TraceIdFilter} and
 * {@link GlobalExceptionHandler}, so the two never drift apart.
 */
final class TraceIds {

  static final String HEADER = "X-Request-Id";
  static final String MDC_KEY = "traceId";

  private TraceIds() {}
}
