package sy.khatm.platform.key.api;

import java.util.List;
import java.util.UUID;

/**
 * SPI for reading a tenant's publishable JWKS material (spec FS-2.1 D8).
 *
 * <p>Backs {@code tenant.web.TenantJwksController}'s {@code GET
 * /t/{tenantSlug}/.well-known/jwks.json} — deliberately in {@code tenant.web}, not {@code key.web}:
 * the {@code tenant} module already depends one-way on {@code key :: api} (onboarding, {@link
 * TenantKeyProvisioner}), so having {@code key} depend back on {@code tenant :: api} to resolve a
 * slug would be a Modulith module cycle. The legacy default-tenant-only {@code GET
 * /.well-known/jwks.json} stays in {@code key.web}, unchanged, calling {@code KeyLifecycleService}
 * directly (same-module).
 */
public interface JwksLookup {

  /**
   * The tenant's publishable ({@code ACTIVE} + {@code RETIRING}) keys, public JWK material only.
   *
   * @param tenantId the tenant to list keys for
   * @return the publishable keys
   */
  List<PublishedKeyView> publishableKeys(UUID tenantId);
}
