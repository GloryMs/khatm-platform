package sy.khatm.platform.rbac.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.rbac.persistence.RoleRepository;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.AuthorizationException;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;

/**
 * Tenant-staff user management (spec FS-2.2 D5/D8) — the surface behind {@code rbac.web
 * .UserAdminController} ({@code /api/v1/users/**}), gated {@code tenant:admin} and scoped to the
 * caller's own tenant via {@link TenantContext} (with Row-Level Security as the database backstop).
 *
 * <p><b>Temporary passwords are plaintext-once:</b> {@link #create} and {@link #resetPassword}
 * return the generated password in a {@link CreatedUser} exactly once — the platform stores only
 * its argon2id hash, the same secret-handling discipline {@code ApiKeyService} applies to API keys,
 * and sets {@code must_change_password} so the first real login is forced through the self-service
 * change step ({@code rbac.security.PasswordChangeEnforcementFilter}).
 *
 * <p><b>The last-tenant-admin guard (D5):</b> {@link #lock}/{@code #disable}/{@link #replaceRoles}
 * refuse (409 {@link ErrorCode#KH_USR_0423}) any change that would leave the tenant with zero
 * {@code ACTIVE} users holding the {@code tenant:admin} scope. The guard is a count-then-act check,
 * which is inherently vulnerable to a lost race (two concurrent locks against the final two
 * administrators would each see the other still active); each guarded method therefore takes a
 * per-tenant Postgres transaction-scoped advisory lock first, serializing the operations within a
 * tenant so the count is always consistent. {@code db.ConcurrentLastAdminTest} proves exactly one
 * of two such concurrent locks succeeds.
 *
 * <p><b>Role-grant ceiling (chore/role-grant-ceiling):</b> {@link #create} and {@link
 * #replaceRoles} refuse any grant whose role carries a scope outside {@link #GRANT_CEILING_SCOPES}
 * unless the real, authenticated calling principal holds {@code platform:admin} — closing a
 * pre-existing gap flagged (out of scope) in KH-2.6b, where a plain {@code tenant:admin} could
 * grant {@code PLATFORM_ADMIN}/{@code ORG_ADMIN} via this same {@code create}/{@code replaceRoles}
 * surface. One chokepoint covers both the local path ({@code rbac.web.UserAdminController}) and the
 * {@code org:admin}-mediated path ({@code rbac.domain.OrgAdminService#createChildUser}, via {@code
 * shared.OnBehalfOfExecutor #runAsChildOrg}) automatically, by construction — neither caller needs
 * its own copy of this check.
 *
 * <p><b>Why the ceiling is pinned to {@code TENANT_ADMIN}'s own scope set, not the literal calling
 * principal's own granted scopes — the one genuinely subtle design point here.</b> Under {@code
 * runAsChildOrg}, this class runs with {@link TenantContext} switched to the <em>child</em> tenant,
 * but the real authenticated principal is still the parent's {@code org:admin} holder — whose own
 * JWT/session scope is exactly, and only, {@code org:admin} ({@code RoleCatalog.ORG_ADMIN}'s single
 * scope). A literal "role's scopes must be a subset of the real caller's own raw scopes" rule,
 * evaluated against that {@code {org:admin}} set, would correctly reject granting {@code ORG_ADMIN}
 * to a child (no self-propagation down the tenant tree — the required outcome) but would
 * <em>also</em> incorrectly reject every legitimate operational-role grant the org-mediated path
 * already makes today (e.g. granting {@code ISSUER_OPERATOR} — {@code
 * rbac.OrgAdminGateTest#createChildUser_writesOrgOnBehalfOf_inParent_andUserCreated_inChild}),
 * since {@code issue}/{@code verify}/{@code revoke} are not in {@code org:admin}'s own raw scope
 * set either. {@code OrgAdminService}'s own class Javadoc already establishes the resolving
 * principle: the org-mediated path is deliberately modeled as "acts exactly as a local {@code
 * tenant:admin} of the child already could, no additional privilege by construction." Pinning the
 * ceiling to {@code TENANT_ADMIN}'s scope set — rather than the caller's own literal scopes — makes
 * that modeling precise for role grants specifically: a real local {@code tenant:admin}'s own raw
 * scopes already <em>equal</em> {@code TENANT_ADMIN}'s scope set (so the local path's behavior is
 * unchanged either way), while the org-mediated path is capped at the exact same ceiling a local
 * {@code tenant:admin} of that same child tenant would have — which is precisely what "acts as if
 * local tenant:admin" means. {@code PLATFORM_ADMIN} (adds {@code platform:admin}) and {@code
 * ORG_ADMIN} (adds {@code org:admin}) are the only two catalog roles that ever exceed this ceiling,
 * so both remain grantable only by an actual {@code platform:admin} holder — on either path.
 * (Session brief's veto point V1-b, the fixed-ceiling fallback, produces the identical outcome for
 * today's four-role catalog; this scope-set form is kept as the general rule since it degrades
 * correctly if the catalog grows a role whose scopes are not a strict superset of {@code
 * TENANT_ADMIN}'s own.)
 *
 * <p>This class is module-private; {@code rbac.web}'s controllers (a different Java package inside
 * the module) and {@code rbac.domain.OrgAdminService} (the org-mediated on-behalf-of plane) are the
 * only callers.
 */
@Service
public class UserAdminService {

  /**
   * The scope whose holders the last-admin guard counts. Duplicated as a string literal rather than
   * reading {@code rbac.security.ScopeRegistry.TENANT_ADMIN} because that constant is
   * package-private to {@code rbac.security} and this domain class cannot depend on it — the same
   * reason {@code shared.OnBehalfOfExecutor} duplicates {@code "SCOPE_platform:admin"}.
   */
  private static final String TENANT_ADMIN_SCOPE = "tenant:admin";

  /**
   * The authenticated caller's {@code platform:admin} authority string, as {@code
   * rbac.security.KhatmAuthorities} builds it. Duplicated rather than imported for the identical
   * Modulith-boundary reason {@link #TENANT_ADMIN_SCOPE} already documents — {@code
   * shared.OnBehalfOfExecutor} duplicates this exact same literal for the exact same reason.
   */
  private static final String PLATFORM_ADMIN_AUTHORITY = "SCOPE_platform:admin";

  /**
   * The role-grant ceiling — see this class's own Javadoc for why it is {@code TENANT_ADMIN}'s
   * scope set specifically, not the calling principal's own literal granted scopes.
   */
  private static final Set<String> GRANT_CEILING_SCOPES = tenantAdminCeilingScopes();

  private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final String BASE62 =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final int TEMP_PASSWORD_LENGTH = 16;

  private final AppUserRepository users;
  private final RoleRepository roles;
  private final PasswordEncoder passwordEncoder;
  private final AuditService audit;
  private final JdbcTemplate jdbc;

  public UserAdminService(
      AppUserRepository users,
      RoleRepository roles,
      PasswordEncoder passwordEncoder,
      AuditService audit,
      JdbcTemplate jdbc) {
    this.users = users;
    this.roles = roles;
    this.passwordEncoder = passwordEncoder;
    this.audit = audit;
    this.jdbc = jdbc;
  }

  /**
   * List the tenant's users, newest creation first.
   *
   * @return every user of the current tenant, newest first
   */
  @Transactional(readOnly = true)
  public List<UserSummary> list() {
    UUID tenantId = TenantContext.current();
    return users.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
        .map(this::toSummary)
        .toList();
  }

  /**
   * Whether a username already exists in the current tenant — used by the onboarding
   * orchestration's resume path to avoid duplicating an initial admin that a prior partial
   * onboarding already created (spec FS-2.2 D6: "fills whatever is missing, never duplicates").
   *
   * @param username the username to check
   * @return {@code true} if a user with this username exists in the current tenant
   */
  @Transactional(readOnly = true)
  public boolean hasUser(String username) {
    return users.findByTenantIdAndUsername(TenantContext.current(), username).isPresent();
  }

  /**
   * Create a tenant user with a generated temporary password. The password is returned exactly once
   * ({@link CreatedUser#temporaryPassword}) and the {@code must_change_password} flag is set.
   *
   * @param username a valid username slug
   * @param displayNameI18n bilingual display name (both languages required)
   * @param roleCodes role codes from the fixed seeded catalog (may be empty for a login-only user)
   * @return the new user's id, username, and one-time temporary password
   * @throws ValidationException {@code KH-USR-0400} if the username format is invalid or a role
   *     code is not in the catalog
   * @throws ConflictException {@code KH-USR-0409} if the username already exists in this tenant
   * @throws AuthorizationException {@code KH-USR-2403} if a requested role exceeds the real calling
   *     principal's own role-grant ceiling (see this class's own Javadoc)
   */
  @Transactional
  public CreatedUser create(String username, LocalizedText displayNameI18n, Set<String> roleCodes) {
    UUID tenantId = TenantContext.current();
    validateUsername(username);
    if (users.findByTenantIdAndUsername(tenantId, username).isPresent()) {
      throw new ConflictException(ErrorCode.KH_USR_0409, "user.duplicate-username");
    }
    Set<String> codes = normalize(roleCodes);
    List<Role> roleEntities = resolveRoles(tenantId, codes);
    ensureGrantWithinCeiling(username, roleEntities);

    String temporaryPassword = generateTemporaryPassword();
    AppUser user = new AppUser();
    user.setId(Uuidv7.generate());
    user.setTenantId(tenantId);
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
    user.setDisplayNameI18n(displayNameI18n);
    user.setPreferredLang("ar");
    user.setStatus(AppUser.STATUS_ACTIVE);
    user.setMustChangePassword(true);
    user.setCreatedAt(Instant.now());
    try {
      users.saveAndFlush(user);
    } catch (DataIntegrityViolationException raced) {
      // A concurrent create of the same username won the (tenant_id, username) race; report it as a
      // conflict rather than surfacing the constraint violation (the transaction rolls back either
      // way — no partial user is left).
      throw new ConflictException(ErrorCode.KH_USR_0409, "user.duplicate-username");
    }
    for (Role role : roleEntities) {
      roles.assignRole(user.getId(), role.getId());
    }
    audit.record(
        AuditAction.USER_CREATED, "app_user", username, Map.of("roles", List.copyOf(codes)));
    return new CreatedUser(user.getId(), username, temporaryPassword);
  }

  /**
   * Replace a user's role set entirely.
   *
   * @param userId the target user
   * @param roleCodes the new role codes (may be empty to remove all roles)
   * @return the user's updated view
   * @throws NotFoundException {@code KH-USR-0404} if the user does not exist in this tenant
   * @throws ValidationException {@code KH-USR-0400} if a role code is not in the catalog
   * @throws AuthorizationException {@code KH-USR-2403} if a requested role exceeds the real calling
   *     principal's own role-grant ceiling (see this class's own Javadoc)
   * @throws ConflictException {@code KH-USR-0423} if this would remove the last active
   *     administrator
   */
  @Transactional
  public UserSummary replaceRoles(UUID userId, Set<String> roleCodes) {
    UUID tenantId = TenantContext.current();
    lockTenantForUserMutation(tenantId);
    AppUser user = requireUser(tenantId, userId);
    Set<String> codes = normalize(roleCodes);
    List<Role> newRoles = resolveRoles(tenantId, codes);
    ensureGrantWithinCeiling(user.getUsername(), newRoles);
    boolean newGrantsAdmin =
        newRoles.stream()
            .anyMatch(role -> Arrays.asList(role.getScopes()).contains(TENANT_ADMIN_SCOPE));
    // Guard before the delete: findScopesByUserId reads the user's CURRENT roles.
    ensureNotLastActiveAdmin(tenantId, user, newGrantsAdmin);
    roles.deleteAllByUserId(user.getId());
    for (Role role : newRoles) {
      roles.assignRole(user.getId(), role.getId());
    }
    audit.record(
        AuditAction.USER_ROLES_CHANGED,
        "app_user",
        user.getUsername(),
        Map.of("roles", List.copyOf(codes)));
    return new UserSummary(
        user.getId(),
        user.getUsername(),
        user.getDisplayNameI18n(),
        user.getStatus(),
        newRoles.stream().map(Role::getCode).toList(),
        user.getCreatedAt());
  }

  /**
   * Lock a user ({@code ACTIVE} → {@code LOCKED}).
   *
   * @throws ConflictException {@code KH-USR-0423} if this is the last active administrator
   */
  @Transactional
  public UserSummary lock(UUID userId) {
    return setStatus(userId, AppUser.STATUS_LOCKED, AuditAction.USER_LOCKED);
  }

  /**
   * Disable a user (→ {@code DISABLED}).
   *
   * @throws ConflictException {@code KH-USR-0423} if this is the last active administrator
   */
  @Transactional
  public UserSummary disable(UUID userId) {
    return setStatus(userId, AppUser.STATUS_DISABLED, AuditAction.USER_DISABLED);
  }

  /**
   * Restore a locked/disabled user to {@code ACTIVE}. No last-admin guard — unlocking can only add
   * an active administrator, never remove one.
   */
  @Transactional
  public UserSummary unlock(UUID userId) {
    UUID tenantId = TenantContext.current();
    AppUser user = requireUser(tenantId, userId);
    if (AppUser.STATUS_ACTIVE.equals(user.getStatus())) {
      return toSummary(user); // idempotent
    }
    user.setStatus(AppUser.STATUS_ACTIVE);
    users.save(user);
    audit.record(AuditAction.USER_UNLOCKED, "app_user", user.getUsername(), null);
    return toSummary(user);
  }

  /**
   * Reset a user's password to a new temporary one (shown once) and force a change at next login.
   *
   * @throws NotFoundException {@code KH-USR-0404} if the user does not exist in this tenant
   */
  @Transactional
  public CreatedUser resetPassword(UUID userId) {
    UUID tenantId = TenantContext.current();
    AppUser user = requireUser(tenantId, userId);
    String temporaryPassword = generateTemporaryPassword();
    user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
    user.setMustChangePassword(true);
    users.save(user);
    audit.record(AuditAction.USER_PASSWORD_RESET, "app_user", user.getUsername(), null);
    return new CreatedUser(user.getId(), user.getUsername(), temporaryPassword);
  }

  /**
   * A user sets their own real password — the one call allowed while {@code must_change_password}
   * is set, and the call that clears it.
   *
   * @param userId the authenticated user (from the principal, never the request body)
   * @param currentPassword the user's current password, verified before anything is changed
   * @param newPassword the new password
   * @throws ValidationException {@code KH-USR-0400} if {@code currentPassword} does not match
   */
  @Transactional
  public void changeOwnPassword(UUID userId, String currentPassword, String newPassword) {
    AppUser user =
        users
            .findById(userId)
            .orElseThrow(() -> new NotFoundException(ErrorCode.KH_USR_0404, "user.not-found"));
    if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
      throw new ValidationException(
          ErrorCode.KH_USR_0400, "user.validation-failed", "current-password");
    }
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setMustChangePassword(false);
    users.save(user);
    audit.record(AuditAction.USER_PASSWORD_CHANGED, "app_user", user.getUsername(), null);
  }

  // ── Helpers ────────────────────────────────────────────────────────────────────────────────

  private UserSummary setStatus(UUID userId, String targetStatus, AuditAction action) {
    UUID tenantId = TenantContext.current();
    lockTenantForUserMutation(tenantId);
    AppUser user = requireUser(tenantId, userId);
    if (targetStatus.equals(user.getStatus())) {
      return toSummary(user); // idempotent no-op
    }
    // lock/disable move the user out of ACTIVE; the guard decides. (unlock has its own path.)
    ensureNotLastActiveAdmin(tenantId, user, false);
    user.setStatus(targetStatus);
    users.save(user);
    audit.record(action, "app_user", user.getUsername(), null);
    return toSummary(user);
  }

  private void ensureNotLastActiveAdmin(
      UUID tenantId, AppUser target, boolean targetRemainsActiveAdmin) {
    if (!AppUser.STATUS_ACTIVE.equals(target.getStatus())) {
      return; // not active now → the operation cannot reduce the active-admin count
    }
    if (!roles.findScopesByUserId(target.getId()).contains(TENANT_ADMIN_SCOPE)) {
      return; // not an administrator → the operation does not touch the active-admin count
    }
    if (targetRemainsActiveAdmin) {
      return; // keeps this user an active administrator (e.g. a role change still granting
      // tenant:admin)
    }
    if (users.countActiveAdminsExcluding(tenantId, target.getId()) == 0) {
      throw new ConflictException(ErrorCode.KH_USR_0423, "user.last-admin");
    }
  }

  /**
   * The role-grant ceiling gate (chore/role-grant-ceiling) — see this class's own Javadoc for the
   * full rationale. A {@code platform:admin} holder bypasses it entirely (grants anything, by
   * construction); any other real caller may only grant roles whose scopes are all within {@link
   * #GRANT_CEILING_SCOPES}. Rejects on the <em>first</em> offending scope found, audited
   * independently (this call's own transaction is about to roll back via the thrown exception).
   *
   * @param targetUsername the user the roles are being granted to — the audit row's {@code
   *     entityRef}
   * @param roleEntities the already-catalog-validated roles being granted (from {@link
   *     #resolveRoles})
   * @throws AuthorizationException {@code KH-USR-2403} on the first role whose scope set is not
   *     entirely within the ceiling
   */
  private void ensureGrantWithinCeiling(String targetUsername, List<Role> roleEntities) {
    if (callerHoldsPlatformAdmin()) {
      return;
    }
    for (Role role : roleEntities) {
      for (String scope : role.getScopes()) {
        if (!GRANT_CEILING_SCOPES.contains(scope)) {
          audit.recordIndependently(
              AuditAction.ROLE_GRANT_REJECTED,
              "app_user",
              targetUsername,
              Map.of("roleCode", role.getCode(), "scope", scope));
          throw new AuthorizationException(
              ErrorCode.KH_USR_2403, "user.role-grant-exceeds-ceiling", role.getCode());
        }
      }
    }
  }

  private static boolean callerHoldsPlatformAdmin() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
        && authentication.getAuthorities().stream()
            .anyMatch(authority -> PLATFORM_ADMIN_AUTHORITY.equals(authority.getAuthority()));
  }

  private static Set<String> tenantAdminCeilingScopes() {
    return RoleCatalog.ALL.stream()
        .filter(definition -> "TENANT_ADMIN".equals(definition.code()))
        .findFirst()
        .map(definition -> Set.copyOf(definition.scopes()))
        .orElseThrow(
            () -> new IllegalStateException("RoleCatalog is missing its TENANT_ADMIN definition"));
  }

  /**
   * Take a per-tenant, transaction-scoped Postgres advisory lock so two concurrent guarded
   * operations (lock/disable/role-change) against the same tenant serialize — the count-then-act
   * last-admin guard is only safe under that serialization. {@code hashtext} collisions across
   * tenants are harmless (they only cause unnecessary serialization, never a correctness gap); the
   * lock auto-releases at the enclosing transaction's commit/rollback.
   */
  private void lockTenantForUserMutation(UUID tenantId) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)", Object.class, tenantId.toString());
  }

  private AppUser requireUser(UUID tenantId, UUID userId) {
    return users
        .findById(userId)
        .filter(u -> tenantId.equals(u.getTenantId()))
        .orElseThrow(() -> new NotFoundException(ErrorCode.KH_USR_0404, "user.not-found"));
  }

  private List<Role> resolveRoles(UUID tenantId, Set<String> codes) {
    if (codes.isEmpty()) {
      return List.of();
    }
    Set<String> unknown = new HashSet<>(codes);
    unknown.removeAll(RoleCatalog.codes());
    if (!unknown.isEmpty()) {
      throw new ValidationException(ErrorCode.KH_USR_0400, "user.validation-failed", "roles");
    }
    List<Role> found = roles.findByTenantIdAndCodeIn(tenantId, codes);
    if (found.size() != codes.size()) {
      // A requested code is a valid catalog code but this tenant's catalog is incomplete — V12 and
      // the onboarding seeder ensure this never happens; treat it as a validation failure
      // regardless.
      throw new ValidationException(ErrorCode.KH_USR_0400, "user.validation-failed", "roles");
    }
    return found;
  }

  private static void validateUsername(String username) {
    if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
      throw new ValidationException(ErrorCode.KH_USR_0400, "user.validation-failed", "username");
    }
  }

  private static Set<String> normalize(Set<String> roleCodes) {
    return roleCodes == null ? Set.of() : roleCodes;
  }

  private UserSummary toSummary(AppUser user) {
    return new UserSummary(
        user.getId(),
        user.getUsername(),
        user.getDisplayNameI18n(),
        user.getStatus(),
        roles.findRoleCodesByUserId(user.getId()),
        user.getCreatedAt());
  }

  private static String generateTemporaryPassword() {
    StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
    for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
      sb.append(BASE62.charAt(SECURE_RANDOM.nextInt(BASE62.length())));
    }
    return sb.toString();
  }
}
