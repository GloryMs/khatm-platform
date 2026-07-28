package sy.khatm.platform.shared;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.AuthorizationException;
import sy.khatm.platform.shared.error.ErrorCode;

/**
 * Runs an action with {@link TenantContext} switched to an explicit target tenant for the duration
 * of the caller's own units of work — the mechanism a {@code platform:admin} caller uses to touch a
 * <em>different</em> tenant's RLS-protected data (spec FS-2.2 D4).
 *
 * <p><b>Only for the enumerated, genuinely cross-tenant admin call sites</b> — today exactly one:
 * {@code rbac.web.AuthController#createApiKey}'s explicit-{@code tenantId} branch (minting a TENANT
 * API key for a tenant other than the caller's own). That endpoint also serves a self-service
 * {@code tenant:admin} caller (no/own {@code tenantId}) on the identical path, so the {@code
 * platform:admin} split for its cross-tenant branch can only live in code — a {@code
 * SecurityConfig} URL-pattern rule cannot inspect a request body. {@code
 * tenant.domain.TenantAdminService#create} deliberately does NOT go through this class: {@code
 * /api/v1/admin/tenants/**} is already {@code platform:admin} -exclusive at the HTTP boundary with
 * no other caller, so an in-service re-check there would be pure redundancy (and would break {@code
 * tenant.domain.TenantAdminServiceTest}'s no-{@code SecurityContext} service-level tests — see that
 * class's own Javadoc). {@code shared.OnBehalfOfCallerAllowlistTest} asserts the set of call sites
 * matches this enumeration exactly, the same discipline {@link SystemAccessExecutor}'s own
 * allowlist test applies.
 *
 * <p><b>Authorization happens here, not only at the controller boundary</b> — this class re-checks
 * {@code platform:admin} itself via the current {@link Authentication}'s authorities (the same
 * {@code SCOPE_<scope>} convention {@code rbac.security.KhatmAuthorities} builds — duplicated here
 * rather than imported, since {@code shared} cannot depend on the module-private {@code
 * rbac.security} package without an illegal reverse Modulith edge; {@code
 * shared.audit.AuditService} already reads {@link SecurityContextHolder} directly for the identical
 * reason). This is what actually closes the gap: before this class existed, {@code
 * rbac.domain.ApiKeyService#create(.., UUID)}'s explicit-tenant overload trusted any caller holding
 * the endpoint's baseline scope with no cross-tenant check of its own.
 *
 * <p><b>Not {@code @Transactional}, deliberately</b> — mirrors {@code
 * rbac.domain.ApiKeyService#create(.., UUID)}'s own manual {@code TenantContext.set}/{@code clear}
 * pattern (which this class wraps, not replaces): {@code action} is expected to invoke its own
 * independently {@code @Transactional} calls, each opening a fresh physical transaction so {@code
 * TenantContextTransactionExecutionListener} re-fires and picks up the target tenant just set here
 * — wrapping this method itself in one transaction would begin it (and fire the listener with the
 * caller's own ambient tenant) before {@link TenantContext#set} ever runs.
 *
 * <p>The audit row is written <em>before</em> the context switch — under the calling admin's own
 * ambient tenant (their own audit trail), with {@code entityRef} identifying which tenant was acted
 * upon, per spec D4's own wording. See {@link AuditAction#ON_BEHALF_OF}.
 */
@Component
public class OnBehalfOfExecutor {

  private static final String PLATFORM_ADMIN_AUTHORITY = "SCOPE_platform:admin";

  private final AuditService audit;

  public OnBehalfOfExecutor(AuditService audit) {
    this.audit = audit;
  }

  /**
   * Run {@code action} on behalf of {@code targetTenantId}/{@code targetTenantSlug} — the caller
   * must already have resolved and validated the target tenant (this class has no dependency on the
   * {@code tenant} module).
   *
   * @param targetTenantId the target tenant's id
   * @param targetTenantSlug the target tenant's slug
   * @param action the work to perform under the target tenant's context
   * @param <T> the action's result type
   * @return the action's result
   * @throws AuthorizationException {@code KH-RBC-0403} if the current caller does not hold {@code
   *     platform:admin}
   */
  public <T> T runAsTenant(UUID targetTenantId, String targetTenantSlug, Supplier<T> action) {
    requirePlatformAdmin();
    audit.record(AuditAction.ON_BEHALF_OF, "tenant", targetTenantSlug, null);
    TenantContext.set(targetTenantId, targetTenantSlug);
    try {
      return action.get();
    } finally {
      TenantContext.clear();
    }
  }

  private static void requirePlatformAdmin() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean granted =
        authentication != null
            && authentication.getAuthorities().stream()
                .anyMatch(authority -> PLATFORM_ADMIN_AUTHORITY.equals(authority.getAuthority()));
    if (!granted) {
      throw new AuthorizationException(ErrorCode.KH_RBC_0403, "error.rbc.forbidden");
    }
  }
}
