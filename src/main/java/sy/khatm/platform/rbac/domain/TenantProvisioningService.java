package sy.khatm.platform.rbac.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.OnBehalfOfExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.tenant.api.TenantAdmin;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;
import sy.khatm.platform.tenant.api.TenantView;

/**
 * The {@code platform:admin} cross-tenant provisioning orchestration (spec FS-2.2 D4/D6) — the one
 * place a platform administrator reaches into a tenant other than their own to provision it. Lives
 * in {@code rbac} (not {@code tenant}) for the same Modulith-cycle reason {@code
 * rbac.web.ConsumingPartyKeyController} lives in {@code rbac}: the work touches {@code rbac}'s own
 * {@code role}/{@code app_user} tables, and {@code tenant → rbac} would cycle against the existing
 * {@code rbac → tenant :: api} edge. {@code rbac} calling {@code tenant :: api} ({@link
 * TenantAdmin} for onboarding) is the allowed direction, so this class orchestrates both halves
 * from here.
 *
 * <p>Every cross-tenant step runs inside {@link OnBehalfOfExecutor#runAsTenant}, which re-verifies
 * {@code platform:admin} against the live principal and records {@code AuditAction.ON_BEHALF_OF}
 * (entityRef = the target tenant's slug) before switching {@code TenantContext} to the target — the
 * exact D4 shape, now genuinely exercising it (unlike {@code TenantAdmin#create}, which is already
 * {@code platform:admin}-exclusive at the HTTP boundary and deliberately bypasses it).
 *
 * <p><b>Resumable onboarding (spec V3), extended at this orchestration layer:</b> {@link
 * TenantAdmin#create}'s own resumability covers exactly "the tenant row exists but has no {@code
 * ACTIVE} signing key yet" — it throws {@code KH-TNT-0409} once the tenant is fully onboarded (row
 * + key), and every pre-existing caller of that method (the plain {@code POST
 * /api/v1/admin/tenants} with no {@code initialAdmin}) relies on that 409 for a genuine
 * duplicate-slug retry — that contract is preserved unchanged. This orchestration adds exactly one
 * new resumable case, scoped to when an {@code initialAdminUsername} is actually requested: a prior
 * {@code initialAdmin} onboard can complete {@code TenantAdmin#create} in full and then die before
 * the role catalog / admin are provisioned. Retrying {@link #onboard} with the same slug AND an
 * {@code initialAdminUsername} resolves the already-onboarded tenant by slug instead of propagating
 * {@code KH-TNT-0409}, and resumes the rbac-side half (catalog + admin) as it would have if {@code
 * create} had returned normally — the role catalog (via {@link RoleCatalogSeeder#ensureCatalog},
 * idempotent) and/or the first admin (created only if absent, never duplicated). The temporary
 * password is returned only when the admin is freshly created; a resumed admin that already exists
 * is left in place (its one-time password is unrecoverable — only its hash was ever stored). A
 * retry with no {@code initialAdminUsername} against an already-onboarded slug has no new rbac-side
 * work to resume, so it is left to surface {@code KH-TNT-0409} exactly as before.
 */
@Service
public class TenantProvisioningService {

  /** The role an onboarding tenant's first administrator is granted (spec FS-2.2 D6). */
  static final String INITIAL_ADMIN_ROLE = "TENANT_ADMIN";

  private final TenantAdmin tenantAdmin;
  private final TenantDirectory tenants;
  private final OnBehalfOfExecutor onBehalfOf;
  private final RoleCatalogSeeder roleCatalogSeeder;
  private final UserAdminService userAdmin;

  public TenantProvisioningService(
      TenantAdmin tenantAdmin,
      TenantDirectory tenants,
      OnBehalfOfExecutor onBehalfOf,
      RoleCatalogSeeder roleCatalogSeeder,
      UserAdminService userAdmin) {
    this.tenantAdmin = tenantAdmin;
    this.tenants = tenants;
    this.onBehalfOf = onBehalfOf;
    this.roleCatalogSeeder = roleCatalogSeeder;
    this.userAdmin = userAdmin;
  }

  /**
   * Onboard a tenant and, optionally, its first administrator. Delegates the tenant row + first
   * signing key + default status list to {@link TenantAdmin#create} (the resumable-onboarding
   * authority, unchanged), then — on behalf of the new tenant — seeds the role catalog and creates
   * the first {@code TENANT_ADMIN} with a one-time temporary password if requested and not already
   * present.
   *
   * @param slug lowercase machine slug
   * @param nameI18n bilingual display name
   * @param type {@code GOVERNMENT}/{@code EDUCATION}/{@code PRIVATE}/{@code OTHER}
   * @param deployMode {@code SAAS}/{@code ONPREM}/{@code FEDERATED}; {@code null} defaults to
   *     {@code SAAS}
   * @param initialAdminUsername the first admin's username, or {@code null} for no initial admin
   * @param initialAdminName the first admin's bilingual display name; ignored when {@code
   *     initialAdminUsername} is {@code null}
   * @return the onboarded (or resumed) tenant plus the first admin's one-time password (when
   *     freshly created)
   */
  public OnboardTenantResult onboard(
      String slug,
      LocalizedText nameI18n,
      String type,
      String deployMode,
      String initialAdminUsername,
      LocalizedText initialAdminName) {
    // TenantAdmin#create() sets TenantContext to the (possibly brand-new) target tenant for its own
    // provisioning steps, then clears it in its own finally block — clear() removes the ThreadLocal
    // entirely rather than restoring whatever this method's caller (TenantContextFilter, for the
    // calling platform admin's own tenant) had set. runAsTenant below needs that ambient value
    // alive
    // to write its pre-switch ON_BEHALF_OF audit row under the CALLER's own tenant (spec D4's own
    // wording), so it must be captured before create() runs and restored before runAsTenant reads
    // it.
    UUID callerTenantId = TenantContext.current();
    String callerTenantSlug = TenantContext.currentSlug();
    TenantView onboarded;
    try {
      onboarded = tenantAdmin.create(slug, nameI18n, type, deployMode);
    } catch (ConflictException alreadyFullyOnboarded) {
      // See this class's own Javadoc "Resumable onboarding" section: only worth resuming through
      // when there is new rbac-side work this call itself is asking for (an initialAdmin) — a plain
      // re-create with none keeps the pre-existing KH-TNT-0409 duplicate-slug contract unchanged.
      if (initialAdminUsername == null) {
        throw alreadyFullyOnboarded;
      }
      TenantRef ref = tenants.findBySlug(slug).orElseThrow(() -> alreadyFullyOnboarded);
      onboarded = tenantAdmin.get(ref.id());
    }
    TenantView tenant = onboarded;
    TenantContext.set(callerTenantId, callerTenantSlug);
    return onBehalfOf.runAsTenant(
        tenant.id(),
        tenant.slug(),
        () -> provisionInitialStaff(tenant, initialAdminUsername, initialAdminName));
  }

  /**
   * Create a user in an <em>existing</em> tenant other than the caller's own (spec FS-2.2 D6,
   * {@code POST /api/v1/admin/tenants/{id}/users}) — the same creation shape as a tenant admin's
   * own-user create, run on behalf of the named tenant. The temporary password is returned once.
   *
   * @param tenantId the target tenant
   * @param username a valid username slug
   * @param nameI18n bilingual display name
   * @param roleCodes role codes from the target tenant's catalog
   * @return the new user's id, username, and one-time temporary password
   * @throws NotFoundException {@code KH-TNT-0404} if the target tenant does not exist
   */
  public CreatedUser createUserInTenant(
      UUID tenantId, String username, LocalizedText nameI18n, Set<String> roleCodes) {
    TenantRef target =
        tenants
            .findById(tenantId)
            .orElseThrow(() -> new NotFoundException(ErrorCode.KH_TNT_0404, "tenant.not-found"));
    return onBehalfOf.runAsTenant(
        target.id(), target.slug(), () -> userAdmin.create(username, nameI18n, roleCodes));
  }

  /**
   * List the users of a tenant other than the caller's own (spec FS-2.2, {@code GET
   * /api/v1/admin/tenants/{id}/users}) — the identical row shape {@code GET /api/v1/users} returns
   * for a tenant admin's own tenant, run on behalf of the named tenant.
   *
   * @param tenantId the target tenant
   * @return every user of that tenant, newest first
   * @throws NotFoundException {@code KH-TNT-0404} if the target tenant does not exist
   */
  public List<UserSummary> listUsersInTenant(UUID tenantId) {
    TenantRef target =
        tenants
            .findById(tenantId)
            .orElseThrow(() -> new NotFoundException(ErrorCode.KH_TNT_0404, "tenant.not-found"));
    return onBehalfOf.runAsTenant(target.id(), target.slug(), userAdmin::list);
  }

  private OnboardTenantResult provisionInitialStaff(
      TenantView tenant, String username, LocalizedText name) {
    // Runs under the new tenant's context (OnBehalfOfExecutor switched to it). The role catalog is
    // ensured first so the INITIAL_ADMIN_ROLE below resolves — idempotent, so a resumed onboarding
    // that already seeded it is a no-op.
    roleCatalogSeeder.ensureCatalog(tenant.id());
    if (username == null) {
      return new OnboardTenantResult(tenant, null, null);
    }
    if (userAdmin.hasUser(username)) {
      // Resume: a prior partial onboarding already created this admin — never duplicate. The
      // one-time password is unrecoverable (only its hash was stored); caller must reset if needed.
      return new OnboardTenantResult(tenant, username, null);
    }
    CreatedUser created = userAdmin.create(username, name, Set.of(INITIAL_ADMIN_ROLE));
    return new OnboardTenantResult(tenant, created.username(), created.temporaryPassword());
  }
}
