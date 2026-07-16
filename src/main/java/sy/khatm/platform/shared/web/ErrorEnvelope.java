package sy.khatm.platform.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * The single, uniform error response shape every non-2xx API response uses (CLAUDE.md work rule 3,
 * spec FS-0.6a D4). Produced exclusively by {@code GlobalExceptionHandler} — no other code
 * constructs one (spec FS-0.6a D8).
 *
 * <p>Referenced directly from other modules' OpenAPI annotations as the shared error-response
 * schema (this is the one type in {@code shared :: web} other modules actually need to import —
 * everything else here, {@code GlobalExceptionHandler} and {@code TraceIdFilter}, wires itself in
 * automatically via Spring's component scanning and {@code @RestControllerAdvice} mechanism).
 *
 * @param code the {@code ErrorCode} wire value, e.g. {@code KH-CRD-0404}
 * @param messageKey the {@code MessageSource} key {@code message} was resolved from — clients that
 *     want to re-localize instead of trusting the server's locale choice can use this
 * @param message {@code messageKey} resolved to the request's locale (spec FS-0.6a D5)
 * @param traceId the same value returned in the {@code X-Request-Id} response header, and the one
 *     that appears on this request's log lines (spec FS-0.6a D6)
 * @param timestamp when the error was handled
 * @param details field-level validation failures; empty (never {@code null}) when not applicable
 */
@Schema(
    name = "ErrorEnvelope",
    description = "Uniform error response for every non-2xx API response")
public record ErrorEnvelope(
    String code,
    String messageKey,
    String message,
    String traceId,
    Instant timestamp,
    List<ErrorDetail> details) {}
