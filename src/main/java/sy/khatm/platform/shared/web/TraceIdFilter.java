package sy.khatm.platform.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps every request with a trace id, before anything else runs (spec FS-0.6a D6).
 *
 * <p>Accepts an inbound {@code X-Request-Id} header if present (so a caller — the console, an
 * upstream gateway — can propagate its own trace id through the platform); otherwise generates a
 * fresh UUID. Either way, the value is placed in the SLF4J MDC under {@code traceId} (picked up by
 * every log line via the logging pattern/encoder, see {@code logback-spring.xml}) and echoed back
 * in the {@code X-Request-Id} response header, so a client can correlate its request with both the
 * response and, if needed, server-side logs.
 *
 * <p>{@code GlobalExceptionHandler} reads the same MDC value to populate the error envelope's
 * {@code traceId} — since it runs within the same request thread inside this filter's {@code
 * doFilter} call, the MDC value is already set by the time any exception handling happens.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceId = request.getHeader(TraceIds.HEADER);
    if (traceId == null || traceId.isBlank()) {
      traceId = UUID.randomUUID().toString();
    }
    MDC.put(TraceIds.MDC_KEY, traceId);
    response.setHeader(TraceIds.HEADER, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      // Removed, not just overwritten — servlet container threads are pooled and reused across
      // unrelated requests; leaving a stale value would misattribute the next request's logs.
      MDC.remove(TraceIds.MDC_KEY);
    }
  }
}
