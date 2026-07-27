package sy.khatm.platform.key.api;

import java.util.UUID;

/**
 * SPI for provisioning a brand-new tenant's first signing key (spec FS-2.1 D6).
 *
 * <p>Deliberately narrow — unlike a general rotation/lifecycle surface, this exposes exactly one
 * operation the {@code tenant} module's onboarding orchestration needs, preserving {@link
 * KeySigner}/{@link KeyVerifier}'s existing invariant that other modules never see rotation, only
 * "sign this" or, now, "provision this brand-new tenant's first key."
 */
public interface TenantKeyProvisioner {

  /**
   * Ensure {@code tenantId} has an {@code ACTIVE} signing key, creating one if it doesn't yet
   * exist.
   *
   * <p>Idempotent: safe to call again on a tenant that already has an {@code ACTIVE} key (the
   * resumable-onboarding path, spec V3) — returns that key's summary rather than creating a second
   * one or throwing.
   *
   * @param tenantId the tenant to provision
   * @param tenantSlug the tenant's slug, used to build the key's {@code kid}
   * @return the tenant's {@code ACTIVE} key summary (freshly created, or the pre-existing one)
   */
  IssuerKeySummaryView provisionFirstKey(UUID tenantId, String tenantSlug);

  /**
   * Whether {@code tenantId} already has an {@code ACTIVE} signing key — {@code
   * tenant.domain.TenantAdminService#create} uses this to distinguish a genuine duplicate-slug
   * conflict (spec {@code KH-TNT-0409}: an already-fully-onboarded tenant) from a resumable partial
   * onboarding (a tenant row exists but provisioning died before a key was created, spec V3).
   *
   * @param tenantId the tenant to check
   * @return {@code true} if an {@code ACTIVE} key already exists for this tenant
   */
  boolean hasActiveKey(UUID tenantId);
}
