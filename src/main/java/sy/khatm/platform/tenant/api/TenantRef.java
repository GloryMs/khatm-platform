package sy.khatm.platform.tenant.api;

import java.util.UUID;

/**
 * A resolvable reference to a tenant, as seen from outside the {@code tenant} module (spec FS-2.1
 * D1/D7) — the shape {@code rbac.security.TenantContextFilter}, {@code
 * rbac.domain.ApiKeyService#verify}, and {@code rbac.domain.AuthService#login} resolve a
 * principal's tenant to.
 *
 * @param id the tenant's internal id
 * @param slug the tenant's machine slug, unique platform-wide
 * @param status {@code ACTIVE} or {@code SUSPENDED}
 */
public record TenantRef(UUID id, String slug, String status) {

  private static final String STATUS_ACTIVE = "ACTIVE";

  /**
   * Whether this tenant is currently {@code ACTIVE} — a {@code SUSPENDED} tenant's own principals
   * fail authentication (spec FS-2.1 D7), the same shape KH-1.4.4 established for a suspended
   * consuming party.
   *
   * @return {@code true} if {@link #status()} is {@code ACTIVE}
   */
  public boolean isActive() {
    return STATUS_ACTIVE.equals(status);
  }
}
