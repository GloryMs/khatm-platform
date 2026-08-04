/**
 * Worker-role components of the tenant module — background jobs that run only when {@code
 * khatm.worker.enabled=true} (the {@code worker} runtime image, ADR-09), never in {@code api}.
 *
 * <p>This is a sub-package of the {@code tenant} module, <em>not</em> a separate Spring Modulith
 * module — same rationale as {@code status.worker}'s own package-info.
 *
 * <p><b>Contains:</b> {@link sy.khatm.platform.tenant.worker.TenantKeyProviderSyncHandler} — keeps
 * {@code tenant.key_provider} in sync with the provider a rotation actually landed the tenant's new
 * {@code ACTIVE} key on (spec FS-2.3 D5/D6, veto V3).
 */
package sy.khatm.platform.tenant.worker;
