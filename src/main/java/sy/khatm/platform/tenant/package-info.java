/**
 * Tenant module — multi-tenancy management for the Khatm platform.
 *
 * <p><b>Responsibilities (KH-2.1, spec FS-2.1):</b> tenant lifecycle (onboard, suspend, activate —
 * {@code tenant.domain.TenantAdminService}), tenant resolution by id/slug ({@code
 * tenant.domain.TenantDirectoryService}). Per-request tenant context propagation itself still lives
 * in {@link sy.khatm.platform.shared.TenantContext} (a {@code ThreadLocal}, populated by {@code
 * rbac.security.TenantContextFilter}), not this module — {@code shared} is the one place every
 * module can reach without a cross-module dependency. Per-tenant quotas/feature flags remain out of
 * scope (a later KH-2.x).
 *
 * <p><b>Exposed API</b> ({@code tenant.api}): {@link sy.khatm.platform.tenant.api.TenantDirectory}
 * (read-only lookup by id/slug — the {@code rbac} module's runtime cross-module dependency) and
 * {@link sy.khatm.platform.tenant.api.TenantAdmin} (the admin plane behind {@code
 * /api/v1/admin/tenants}). This module depends one-way on {@code key :: api} ({@code
 * TenantKeyProvisioner}) and {@code status :: api} ({@code StatusListAllocator#ensureList}) for
 * onboarding — deliberately one-way, so {@code key}/{@code status} never depend back on {@code
 * tenant :: api}, which would be a Modulith cycle. The two public, slug-keyed HTTP endpoints that
 * would otherwise need such a reverse dependency ({@code GET /t/{tenantSlug}/.well-known/jwks.json}
 * and {@code GET /sl/{tenantSlug}/{listCode}}, relocated from {@code status.web}) live in {@code
 * tenant.web} instead, for the same reason.
 *
 * <p><b>Published events:</b> (none yet)
 *
 * <p><b>Tables owned:</b> {@code tenant}
 *
 * <p><b>Status:</b> KH-2.1 Part A — tenant context resolution, the admin/onboarding plane, and the
 * per-tenant trust endpoints. RLS enforcement (Part B) is a separate, later change to this same
 * module's migration/persistence layer.
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.tenant;
