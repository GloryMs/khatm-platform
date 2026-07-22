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
 * interface), plus four deliberate {@code @NamedInterface}s: {@code error} (other modules throw
 * {@code KhatmException} subtypes and use {@code VerifyReason} directly), {@code web} (other
 * modules' OpenAPI annotations reference {@code ErrorEnvelope} as the shared error-response
 * schema), {@code events} (the ADR-09 async backbone — other modules implement {@code
 * StreamEventHandler} to consume externalized events from Redis Streams), and {@code audit} (spec
 * FS-0.6b — the single {@code audit_log} write path, {@link
 * sy.khatm.platform.shared.audit.AuditService}). Sub-packages such as {@code config/} remain
 * module-private.
 *
 * <p><b>Stats/counters (KH-1.1.3):</b> {@code web.StatsController} (new) serves {@code GET
 * /api/v1/stats} — the console's C4 pilot-metrics dashboard commitment (spec FS-1.5.3) — as a plain
 * {@code GROUP BY action} read over this module's own {@code audit_log} table via {@link
 * sy.khatm.platform.shared.audit.AuditService#countActionsInWindow}, never a new bookkeeping
 * system. Stays entirely inside this module: the controller only depends on {@code audit}, a
 * same-module named interface, so no new outbound dependency is introduced.
 *
 * <p><b>Tables owned:</b> {@code audit_log} (append-only; the write path is {@code shared ::
 * audit}, KH-0.6b).
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.shared;
