/**
 * Shared module — cross-cutting infrastructure used by all other modules.
 *
 * <p><b>Responsibilities:</b> web configuration (CORS, locale resolution), error envelope types,
 * i18n setup, the {@code name_i18n} / {@code label_i18n} JSONB convention ({@link
 * sy.khatm.platform.shared.LocalizedText}, {@link sy.khatm.platform.shared.LocalizedTextConverter}
 * — CONVENTIONS.md §3), provisional single-tenant context ({@link
 * sy.khatm.platform.shared.TenantContext} — full multi-tenancy is KH-2.1), OpenAPI configuration.
 *
 * <p>This module has NO outbound dependencies on other Khatm modules. It may depend only on Spring
 * framework libraries.
 *
 * <p><b>Exposed API:</b> all public types directly in this package (the implicit unnamed
 * interface). Sub-packages such as {@code config/} are module-private.
 *
 * <p><b>Tables owned:</b> {@code audit_log} (append-only; KH-0.6 / KH-1.6.3 add the write path).
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.shared;
