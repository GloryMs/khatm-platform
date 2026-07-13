/**
 * Tenant module — multi-tenancy management for the Khatm platform.
 *
 * <p><b>Responsibilities:</b> tenant lifecycle (create, suspend, delete), tenant configuration,
 * per-tenant quota and feature flags. Tenant context propagation for the single-tenant MVP is
 * provided via {@link sy.khatm.platform.shared.TenantContext} instead of this module, so that no
 * other module needs to depend on {@code tenant} before KH-2.x.
 *
 * <p><b>Exposed API:</b> (none yet — KH-2.x). {@code Tenant} entity + repository exist only so
 * {@code ddl-auto: validate} covers the {@code tenant} table (KH-0.2.1).
 *
 * <p><b>Published events:</b> (none yet)
 *
 * <p><b>Tables owned:</b> {@code tenant}
 *
 * <p><b>Status:</b> persistence only — business logic (create/suspend, quotas) deferred to KH-2.x.
 */
package sy.khatm.platform.tenant;
