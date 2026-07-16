/**
 * The platform's single error envelope and request-tracing infrastructure (CLAUDE.md work rules 1 &
 * 3, spec FS-0.6a).
 *
 * <p>{@code GlobalExceptionHandler} is the only place in the platform that turns a thrown {@code
 * KhatmException} (or a validation/unexpected failure) into an HTTP response — every module's
 * controllers let exceptions propagate to it rather than building error responses themselves (spec
 * FS-0.6a D8). {@code TraceIdFilter} stamps every request with a trace id (inbound {@code
 * X-Request-Id} if present, else a generated UUID) via MDC, echoed in both the response header and
 * the envelope.
 *
 * <p>A {@code @NamedInterface} ({@code shared :: web}) because {@link
 * sy.khatm.platform.shared.web.ErrorEnvelope} is referenced directly from other modules' OpenAPI
 * annotations (as the shared error-response schema) — everything else here (the filter, the advice
 * itself) other modules never need to import; Spring wires them in automatically via component
 * scanning and the {@code @RestControllerAdvice} mechanism.
 */
@org.springframework.modulith.NamedInterface("web")
package sy.khatm.platform.shared.web;
