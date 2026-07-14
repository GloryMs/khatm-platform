/**
 * Shared module — cross-cutting infrastructure used by all other modules.
 *
 * <p><b>Responsibilities:</b> web configuration (CORS, locale resolution), error envelope types,
 * i18n setup, tenant context propagation (future KH-2.x), OpenAPI configuration.
 *
 * <p>This module has NO outbound dependencies on other Khatm modules. It may depend only on Spring
 * framework libraries.
 *
 * <p><b>Exposed API:</b> all public types in this package and {@code api/} sub-package.
 *
 * <p><b>Tables owned:</b> none
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.shared;
