package sy.khatm.platform.rbac.domain;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.rbac.persistence.RoleRepository;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.AuthenticationException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

/**
 * Console login/logout and the temporary-lockout counter (spec FS-0.6b D1, D6, D7).
 *
 * <p><b>D7 — one generic failure, always:</b> every failure path (unknown username, wrong password,
 * temporarily locked out, administratively {@code LOCKED}/{@code DISABLED}) throws the exact same
 * {@link AuthenticationException} with {@link ErrorCode#KH_RBC_0401}. The real reason is recorded
 * only in the {@code AuditAction#AUTH_LOGIN_FAILED} row's {@code detail} — never in the response,
 * which is how this class resists username-enumeration (STRIDE — Spoofing / Information
 * Disclosure).
 *
 * <p><b>D6 — Redis TTL lockout, independent of the administrative {@code LOCKED} status:</b> {@code
 * khatm:auth:fail:{tenant}:{username}} counts failures with a fixed window from the <em>first</em>
 * failure (the counter's TTL is set once, on the transition from 0 to 1, and never refreshed by
 * later failures within the same window — otherwise a slow drip of attempts could keep a window
 * open indefinitely). Once the counter reaches {@code max-attempts}, every subsequent attempt is
 * rejected — including one with the <em>correct</em> password — until the window elapses, at which
 * point the key expires and login works again with no administrative action.
 *
 * <p>This class is module-private; the login/logout HTTP contract is {@code
 * rbac.web.AuthController}, a different Java package inside the same module — see {@link
 * LoginResult}'s Javadoc for why it (and this class) are {@code public} despite that.
 */
@Service
public class AuthService {

  private static final String LOCKOUT_KEY_PREFIX = "khatm:auth:fail:";

  private final AppUserRepository users;
  private final RoleRepository roles;
  private final PasswordEncoder passwordEncoder;
  private final StringRedisTemplate redis;
  private final AuditService audit;
  private final TenantDirectory tenants;
  private final int maxAttempts;
  private final Duration lockoutWindow;

  public AuthService(
      AppUserRepository users,
      RoleRepository roles,
      PasswordEncoder passwordEncoder,
      StringRedisTemplate redis,
      AuditService audit,
      TenantDirectory tenants,
      @Value("${khatm.auth.lockout.max-attempts:5}") int maxAttempts,
      @Value("${khatm.auth.lockout.window:15m}") Duration lockoutWindow) {
    this.users = users;
    this.roles = roles;
    this.passwordEncoder = passwordEncoder;
    this.redis = redis;
    this.audit = audit;
    this.tenants = tenants;
    this.maxAttempts = maxAttempts;
    this.lockoutWindow = lockoutWindow;
  }

  /**
   * Authenticate a username/password pair, optionally against an explicitly named tenant (spec
   * FS-2.2 — multi-tenant console login).
   *
   * <p><b>Tenant resolution, and why this method is deliberately NOT {@code @Transactional}:</b> a
   * blank/{@code null} {@code tenantSlug} resolves to {@link TenantContext#current()} — for the
   * anonymous request every console login is, that is always the default tenant, preserving this
   * method's exact pre-existing behavior byte for byte. A non-blank {@code tenantSlug} resolves via
   * {@link TenantDirectory#findBySlug}, which needs no ambient tenant context at all ({@code
   * tenant} is the one business table excluded from RLS, spec FS-2.1 D2). Once the target tenant is
   * resolved, {@link TenantContext#set} switches to it for the remainder of this call — and every
   * subsequent step ({@code app_user} lookup, {@code audit.record}) must open its <em>own</em>
   * fresh physical transaction to pick that switch up, exactly the {@code
   * rbac.domain.ApiKeyService#create(.., UUID)} / {@code tenant.domain.TenantAdminService#create}
   * pattern: {@code shared.TenantContextTransactionExecutionListener#afterBegin} fires once per
   * physical transaction and reads whatever {@link TenantContext} holds <em>at that moment</em> — a
   * single outer {@code @Transactional} boundary around this whole method would begin (and fire the
   * listener) before the tenant is even resolved, permanently pinning {@code app.tenant_id} to the
   * caller's ambient default and making a non-default tenant's {@code app_user} row invisible under
   * RLS regardless of the {@link TenantContext#set} call below. {@code
   * rbac.persistence.AppUserRepository}'s type-level {@code @Transactional(readOnly = true)} and
   * {@code shared.audit.AuditService#record}'s own {@code @Transactional} each independently supply
   * that fresh transaction, so no {@code noRollbackFor} is needed either — each audit write commits
   * on its own before this method can throw.
   *
   * @param username the submitted username
   * @param rawPassword the submitted plaintext password — never logged, never persisted
   * @param tenantSlug the tenant to authenticate against, or {@code null}/blank for the caller's
   *     ambient (default) tenant
   * @return the authenticated user's session-establishing details
   * @throws AuthenticationException always with the same generic {@link ErrorCode#KH_RBC_0401}
   *     message (D7), for every failure reason — including an unknown or {@code SUSPENDED} {@code
   *     tenantSlug}, so an unauthenticated caller can never use this endpoint to probe whether a
   *     given tenant slug exists (the same unified-failure anti-enumeration stance D7 already
   *     applies to username/password)
   */
  public LoginResult login(String username, String rawPassword, String tenantSlug) {
    TenantRef tenant = resolveTenant(tenantSlug);

    // Spec FS-2.1 D7 (extended, spec FS-2.2, to an explicitly named tenant): an unknown or
    // SUSPENDED tenant's login attempt gets the identical generic failure as every other reason —
    // checked before anything user-specific so it leaks neither whether the tenant exists nor
    // whether the username/password would otherwise have been valid. An already-existing session
    // surviving a later suspension is a separate gap rbac.security.TenantContextFilter closes.
    if (tenant == null || !tenant.isActive()) {
      audit.record(
          AuditAction.AUTH_LOGIN_FAILED,
          "app_user",
          username,
          Map.of("reason", tenant == null ? "unknown_tenant" : "tenant_suspended"));
      throw unauthenticated();
    }

    TenantContext.set(tenant.id(), tenant.slug());
    try {
      return authenticate(tenant.id(), username, rawPassword);
    } finally {
      TenantContext.clear();
    }
  }

  private TenantRef resolveTenant(String tenantSlug) {
    if (tenantSlug == null || tenantSlug.isBlank()) {
      return tenants.findById(TenantContext.current()).orElse(null);
    }
    return tenants.findBySlug(tenantSlug).orElse(null);
  }

  private LoginResult authenticate(UUID tenantId, String username, String rawPassword) {
    String lockKey = LOCKOUT_KEY_PREFIX + tenantId + ":" + username;

    if (isLockedOut(lockKey)) {
      audit.record(
          AuditAction.AUTH_LOCKOUT_TRIGGERED,
          "app_user",
          username,
          Map.of("reason", "locked_temporarily"));
      throw unauthenticated();
    }

    Optional<AppUser> maybeUser = users.findByTenantIdAndUsername(tenantId, username);
    if (maybeUser.isEmpty()) {
      recordFailure(lockKey);
      audit.record(
          AuditAction.AUTH_LOGIN_FAILED, "app_user", username, Map.of("reason", "unknown_user"));
      throw unauthenticated();
    }

    AppUser user = maybeUser.get();
    if (!user.isActive()) {
      recordFailure(lockKey);
      audit.record(
          AuditAction.AUTH_LOGIN_FAILED,
          "app_user",
          username,
          Map.of("reason", user.getStatus().toLowerCase()));
      throw unauthenticated();
    }

    if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
      recordFailure(lockKey);
      audit.record(
          AuditAction.AUTH_LOGIN_FAILED, "app_user", username, Map.of("reason", "bad_password"));
      throw unauthenticated();
    }

    redis.delete(lockKey);
    Set<String> scopes = new LinkedHashSet<>(roles.findScopesByUserId(user.getId()));
    audit.record(AuditAction.AUTH_LOGIN_SUCCESS, "app_user", username, null);
    return new LoginResult(
        user.getId(),
        user.getUsername(),
        user.getDisplayNameI18n(),
        user.getPreferredLang(),
        scopes,
        tenantId);
  }

  /**
   * Look up a user's display details for {@code GET /api/v1/auth/me} — a fresh read, not cached in
   * the session, so a display-name or language change is reflected without re-login.
   *
   * @param userId the authenticated user's id
   * @return the user's current view, or empty if the account no longer exists
   */
  @Transactional(readOnly = true)
  public Optional<UserView> findUserView(UUID userId) {
    return users
        .findById(userId)
        .map(
            u ->
                new UserView(
                    u.getUsername(),
                    u.getDisplayNameI18n(),
                    u.getPreferredLang(),
                    u.isMustChangePassword()));
  }

  private boolean isLockedOut(String lockKey) {
    String raw = redis.opsForValue().get(lockKey);
    if (raw == null) {
      return false;
    }
    return Integer.parseInt(raw) >= maxAttempts;
  }

  private void recordFailure(String lockKey) {
    Long count = redis.opsForValue().increment(lockKey);
    if (count != null && count == 1L) {
      redis.expire(lockKey, lockoutWindow);
    }
  }

  private static AuthenticationException unauthenticated() {
    return new AuthenticationException(ErrorCode.KH_RBC_0401, "error.rbc.unauthenticated");
  }
}
