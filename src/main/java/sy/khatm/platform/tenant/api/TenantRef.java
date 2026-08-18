package sy.khatm.platform.tenant.api;

import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * A resolvable reference to a tenant, as seen from outside the {@code tenant} module (spec FS-2.1
 * D1/D7) — the shape {@code rbac.security.TenantContextFilter}, {@code
 * rbac.domain.ApiKeyService#verify}, and {@code rbac.domain.AuthService#login} resolve a
 * principal's tenant to.
 *
 * @param id the tenant's internal id
 * @param slug the tenant's machine slug, unique platform-wide
 * @param status {@code ACTIVE} or {@code SUSPENDED}
 * @param nameI18n bilingual display name (KH-2.6a, spec FS-2.5 §5) — added for {@link
 *     TenantDirectory#ancestors}'s lineage-display use case; every pre-existing caller that only
 *     ever read {@link #id()}/{@link #slug()}/{@link #status()} is unaffected.
 */
public record TenantRef(UUID id, String slug, String status, LocalizedText nameI18n) {

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
