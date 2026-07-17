package sy.khatm.platform.rbac.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.web.ErrorEnvelope;

/**
 * Writes a {@link ErrorEnvelope} response directly to the servlet layer (spec FS-0.6b §5) — used by
 * {@link KhatmAuthenticationEntryPoint} and {@link KhatmAccessDeniedHandler}, both of which run
 * <em>inside the Spring Security filter chain, before {@code DispatcherServlet}</em>, so {@code
 * shared.web.GlobalExceptionHandler} (an {@code @RestControllerAdvice}, which only ever sees
 * exceptions thrown from within a controller invocation) never gets a chance to produce this
 * response itself. This class independently builds the identical envelope shape so a client sees
 * exactly one consistent error format regardless of which layer rejected the request.
 *
 * <p>Resolves locale via the {@link LocaleResolver} bean directly, not {@code LocaleContextHolder}
 * — that holder is only populated once {@code DispatcherServlet} starts processing a request, which
 * has not happened yet at this (pre-servlet, filter-chain) layer.
 *
 * <p>Reads the trace id set by {@code shared.web.TraceIdFilter} (ordered {@code
 * HIGHEST_PRECEDENCE}, so it always runs before Spring Security's filter chain) straight from MDC —
 * {@code TraceIds}, the class that owns that MDC key, is package-private in {@code shared.web} and
 * so cannot be imported here; the key name itself ({@code "traceId"}) is a stable, documented
 * contract between the two, not a coincidence.
 */
@Component
class SecurityEnvelopeWriter {

  private static final String TRACE_ID_MDC_KEY = "traceId";

  private final MessageSource messageSource;
  private final LocaleResolver localeResolver;
  private final ObjectMapper objectMapper;

  SecurityEnvelopeWriter(
      MessageSource messageSource, LocaleResolver localeResolver, ObjectMapper objectMapper) {
    this.messageSource = messageSource;
    this.localeResolver = localeResolver;
    this.objectMapper = objectMapper;
  }

  void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode)
      throws IOException {
    Locale locale = localeResolver.resolveLocale(request);
    String message = messageSource.getMessage(errorCode.messageKey(), null, locale);
    String traceId = MDC.get(TRACE_ID_MDC_KEY);

    ErrorEnvelope envelope =
        new ErrorEnvelope(
            errorCode.code(), errorCode.messageKey(), message, traceId, Instant.now(), List.of());

    response.setStatus(errorCode.httpStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getWriter(), envelope);
  }
}
