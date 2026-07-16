package sy.khatm.platform.shared.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.KhatmException;

/**
 * The single, exclusive producer of {@link ErrorEnvelope} responses (CLAUDE.md work rule 3, spec
 * FS-0.6a D8). No controller anywhere builds an error response by hand — every request error either
 * throws a {@link KhatmException} subtype, or is a Bean Validation / genuinely unexpected failure
 * this class handles as a fallback.
 *
 * <p>Logging follows CLAUDE.md's level semantics: a {@link KhatmException} whose {@link
 * ErrorCode#httpStatus()} is 5xx is logged at {@code ERROR} with the full stack trace (it is an
 * internal problem, "needs action"); a 4xx {@link KhatmException} is logged at {@code WARN} without
 * one (it is an anticipated, client-fixable outcome). The catch-all {@link #handleUnexpected} path
 * is always {@code ERROR} with the full stack trace — by definition nothing anticipated it. No log
 * line here ever includes claims, JWTs, keys, or PII (SEC §9) — only the error code, message key,
 * and trace id.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final MessageSource messageSource;

  GlobalExceptionHandler(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  @ExceptionHandler(KhatmException.class)
  ResponseEntity<ErrorEnvelope> handleKhatmException(KhatmException ex) {
    ErrorCode errorCode = ex.errorCode();
    Locale locale = LocaleContextHolder.getLocale();
    String message = messageSource.getMessage(ex.messageKey(), ex.args(), locale);

    if (errorCode.httpStatus().is5xxServerError()) {
      log.error(
          "khatm exception code={} messageKey={} traceId={}",
          errorCode.code(),
          ex.messageKey(),
          currentTraceId(),
          ex);
    } else {
      log.warn(
          "khatm exception code={} messageKey={} traceId={}",
          errorCode.code(),
          ex.messageKey(),
          currentTraceId());
    }

    return ResponseEntity.status(errorCode.httpStatus())
        .body(envelope(errorCode.code(), ex.messageKey(), message, List.of()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException ex) {
    Locale locale = LocaleContextHolder.getLocale();
    List<ErrorDetail> details =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fieldError -> toErrorDetail(fieldError, locale))
            .toList();

    log.warn(
        "validation failed fields={} traceId={}",
        details.stream().map(ErrorDetail::field).toList(),
        currentTraceId());

    String message = messageSource.getMessage(ErrorCode.KH_SYS_0400.messageKey(), null, locale);
    return ResponseEntity.status(ErrorCode.KH_SYS_0400.httpStatus())
        .body(
            envelope(
                ErrorCode.KH_SYS_0400.code(),
                ErrorCode.KH_SYS_0400.messageKey(),
                message,
                details));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorEnvelope> handleUnexpected(Exception ex) {
    String traceId = currentTraceId();
    // Full stack trace, always — this is exactly the case CLAUDE.md work rule 3 means by
    // "needs action." Nothing from `ex` (message, class, trace) ever reaches the client below.
    log.error("unexpected error traceId={}", traceId, ex);

    Locale locale = LocaleContextHolder.getLocale();
    String message = messageSource.getMessage(ErrorCode.KH_SYS_0500.messageKey(), null, locale);
    return ResponseEntity.status(ErrorCode.KH_SYS_0500.httpStatus())
        .body(
            envelope(
                ErrorCode.KH_SYS_0500.code(),
                ErrorCode.KH_SYS_0500.messageKey(),
                message,
                List.of()));
  }

  private ErrorDetail toErrorDetail(FieldError fieldError, Locale locale) {
    String constraint = fieldError.getCode() == null ? "Unknown" : fieldError.getCode();
    String key = "validation." + constraint;
    String message = messageSource.getMessage(key, null, key, locale);
    return new ErrorDetail(fieldError.getField(), key, message);
  }

  private ErrorEnvelope envelope(
      String code, String messageKey, String message, List<ErrorDetail> details) {
    return new ErrorEnvelope(code, messageKey, message, currentTraceId(), Instant.now(), details);
  }

  private static String currentTraceId() {
    return MDC.get(TraceIds.MDC_KEY);
  }
}
