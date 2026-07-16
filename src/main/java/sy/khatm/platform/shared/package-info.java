/**
 * Shared module — cross-cutting infrastructure used by all other modules.
 *
 * <p><b>Responsibilities:</b> web configuration (CORS, locale resolution — {@code config/}), the
 * single error-handling vocabulary and hierarchy (CLAUDE.md work rule 3, spec FS-0.6a — {@link
 * sy.khatm.platform.shared.error.KhatmException} and its six subtypes, {@link
 * sy.khatm.platform.shared.error.ErrorCode}, {@link sy.khatm.platform.shared.error.VerifyReason} —
 * {@code error/}), the sole error-envelope producer and request-tracing filter (work rule 2, spec
 * FS-0.6a — {@code GlobalExceptionHandler}, {@code TraceIdFilter} — {@code web/}), the {@code
 * name_i18n} / {@code label_i18n} JSONB convention ({@link sy.khatm.platform.shared.LocalizedText},
 * {@link sy.khatm.platform.shared.LocalizedTextConverter} — CONVENTIONS.md §3), provisional
 * single-tenant context ({@link sy.khatm.platform.shared.TenantContext} — full multi-tenancy is
 * KH-2.1), OpenAPI configuration.
 *
 * <p>This module has NO outbound dependencies on other Khatm modules. It may depend only on Spring
 * framework libraries.
 *
 * <p><b>Exposed API:</b> all public types directly in this package (the implicit unnamed
 * interface), plus three deliberate {@code @NamedInterface}s: {@code error} (other modules throw
 * {@code KhatmException} subtypes and use {@code VerifyReason} directly), {@code web} (other
 * modules' OpenAPI annotations reference {@code ErrorEnvelope} as the shared error-response
 * schema), and {@code events} (the ADR-09 async backbone — other modules implement {@code
 * StreamEventHandler} to consume externalized events from Redis Streams). Sub-packages such as
 * {@code config/} remain module-private.
 *
 * <p><b>Tables owned:</b> {@code audit_log} (append-only; the write path is KH-0.6b).
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.shared;
