/**
 * Tenant module — multi-tenancy management for the Khatm platform.
 *
 * <p><b>Responsibilities (KH-2.1, spec FS-2.1):</b> tenant lifecycle (onboard, suspend, activate —
 * {@code tenant.domain.TenantAdminService}), tenant resolution by id/slug ({@code
 * tenant.domain.TenantDirectoryService}), and — KH-2.6a, spec FS-2.5 — an optional tenant hierarchy
 * ({@code parent_tenant_id}, max depth three levels per §7): pure organisational metadata (§1),
 * never a security or cryptographic boundary; every tenant, parent or child, still signs with its
 * own key, publishes its own JWKS, and owns its own status list. Per-request tenant context
 * propagation itself still lives in {@link sy.khatm.platform.shared.TenantContext} (a {@code
 * ThreadLocal}, populated by {@code rbac.security.TenantContextFilter}), not this module — {@code
 * shared} is the one place every module can reach without a cross-module dependency. Per-tenant
 * quotas/feature flags remain out of scope (a later KH-2.x).
 *
 * <p><b>Exposed API</b> ({@code tenant.api}): {@link sy.khatm.platform.tenant.api.TenantDirectory}
 * (read-only lookup by id/slug — the {@code rbac} module's runtime cross-module dependency; {@code
 * directChildren}/{@code descendants}, KH-2.6b spec FS-2.5 §3/§4, back the {@code org:admin}
 * plane's direct-children administrative operations and its full-subtree aggregated report,
 * respectively) and {@link sy.khatm.platform.tenant.api.TenantAdmin} (the admin plane behind {@code
 * /api/v1/admin/tenants}; its {@code suspend}/{@code activate} are also reached, unchanged, from
 * {@code rbac.domain.OrgAdminService} once it has independently validated a direct-child
 * relationship). This module depends one-way on {@code key :: api} ({@code TenantKeyProvisioner})
 * and {@code status :: api} ({@code StatusListAllocator#ensureList}) for onboarding — deliberately
 * one-way, so {@code key}/{@code status} never depend back on {@code tenant :: api}, which would be
 * a Modulith cycle. The two public, slug-keyed HTTP endpoints that would otherwise need such a
 * reverse dependency ({@code GET /t/{tenantSlug}/.well-known/jwks.json} and {@code GET
 * /sl/{tenantSlug}/{listCode}}, relocated from {@code status.web}) live in {@code tenant.web}
 * instead, for the same reason.
 *
 * <p><b>Published events:</b> (none yet)
 *
 * <p><b>Events consumed (KH-2.3b, spec FS-2.3 D5/D6):</b> {@code tenant.worker
 * .TenantKeyProviderSyncHandler} consumes {@link sy.khatm.platform.key.events.KeyRotated} to keep
 * {@code tenant.key_provider} — the tenant-level "current signing-key provider" column (veto V3) —
 * in sync with whatever provider a rotation actually landed the tenant's new key on. Does not add a
 * new dependency edge: {@code tenant} already depends one-way on {@code key :: api}.
 *
 * <p><b>Tables owned:</b> {@code tenant}
 *
 * <p><b>Status:</b> KH-2.1 Part A — tenant context resolution, the admin/onboarding plane, and the
 * per-tenant trust endpoints. Part B added real Postgres Row Level Security enforcement ({@code
 * V7__rls_policies.sql}), a locked-down {@code khatm_app} database role, transaction-scoped {@code
 * app.tenant_id} propagation ({@link sy.khatm.platform.shared.TenantContext} → {@code
 * shared.TenantContextTransactionExecutionListener}), and {@code shared.SystemAccessExecutor} for
 * the handful of legitimately-anonymous read paths — see {@code shared}'s package-info and README
 * for the mechanism, since it applies platform-wide, not just to this module's own tables. {@code
 * TenantAdminService#create} runs its key/status-list provisioning steps (and the
 * conflict-vs-resume {@code hasActiveKey} check) under an explicit {@code TenantContext.set} for
 * the target tenant, never the calling admin's own ambient one — RLS would otherwise hide or reject
 * a fresh tenant's own rows.
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.tenant;
