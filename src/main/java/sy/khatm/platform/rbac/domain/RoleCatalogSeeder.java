package sy.khatm.platform.rbac.domain;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.rbac.persistence.RoleRepository;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.Uuidv7;

/**
 * Ensures a tenant has its fixed three-role catalog (spec FS-2.2 D5/D6). Called by the rbac
 * onboarding orchestration right after tenant + signing-key + status-list provisioning, and
 * idempotent so a resumed half-onboarded tenant (one whose prior onboarding died before the catalog
 * was seeded) is filled in rather than left without roles — the resume semantics the spec asks the
 * onboarding extension to honor.
 *
 * <p>Runs under the target tenant's already-set {@code TenantContext} (the caller — {@code
 * shared.OnBehalfOfExecutor#runAsTenant} in the onboarding path — sets it explicitly); {@code role}
 * is RLS-protected, so seeding under any other tenant's ambient context would be hidden or rejected
 * by the {@code tenant_isolation} policy.
 */
@Component
public class RoleCatalogSeeder {

  private final RoleRepository roles;

  public RoleCatalogSeeder(RoleRepository roles) {
    this.roles = roles;
  }

  /**
   * Ensure every catalog role exists for {@code tenantId}, creating any that are missing. A
   * complete no-op for a tenant that already has the full catalog (the default tenant, or a resumed
   * tenant whose catalog was already seeded) — find-or-create per role, so a resume fills exactly
   * what is missing and nothing more.
   *
   * @param tenantId the tenant whose catalog to ensure
   */
  @Transactional
  public void ensureCatalog(UUID tenantId) {
    for (RoleCatalog.Definition def : RoleCatalog.ALL) {
      if (roles.findByTenantIdAndCode(tenantId, def.code()).isEmpty()) {
        roles.save(newRole(tenantId, def));
      }
    }
  }

  private static Role newRole(UUID tenantId, RoleCatalog.Definition def) {
    Role role = new Role();
    role.setId(Uuidv7.generate());
    role.setTenantId(tenantId);
    role.setCode(def.code());
    role.setNameI18n(new LocalizedText(def.nameEn(), def.nameAr()));
    role.setScopes(def.scopes().toArray(new String[0]));
    return role;
  }
}
