package sy.khatm.platform.rbac.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
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
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.AuthenticationException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

/**
 * Console login/logout, the temporary-lockout counter (spec FS-0.6b D1, D6, D7), and the TOTP
 * login-challenge step (spec FS-2.2 V1).
 *
 * <p><b>D7 — one generic failure, always:</b> every failure path (unknown username, wrong password,
 * temporarily locked out, administratively {@code LOCKED}/{@code DISABLED}, a wrong TOTP code/
 * recovery code, or a TOTP-attempt lockout) throws the exact same {@link AuthenticationException}
 * with {@link ErrorCode#KH_RBC_0401}. The real reason is recorded only in the {@code
 * AuditAction#AUTH_LOGIN_FAILED} row's {@code detail} — never in the response, which is how this
 * class resists username-enumeration (STRIDE — Spoofing / Information Disclosure).
 *
 * <p><b>D6 — Redis TTL lockout, independent of the administrative {@code LOCKED} status:</b> {@code
 * khatm:auth:fail:{tenant}:{username}} counts failures with a fixed window from the <em>first</em>
 * failure (the counter's TTL is set once, on the transition from 0 to 1, and never refreshed by
 * later failures within the same window — otherwise a slow drip of attempts could keep a window
 * open indefinitely). Once the counter reaches {@code max-attempts}, every subsequent attempt is
 * rejected — including one with the <em>correct</em> password — until the window elapses, at which
 * point the key expires and login works again with no administrative action.
 *
 * <p><b>KH-2.2c — TOTP login challenge (spec FS-2.2 V1):</b> when the password is correct but the
 * user has an active (confirmed) TOTP enrollment, {@link #login} does not complete the login — it
 * returns {@link LoginOutcome.TotpChallenge}, an opaque, single-use, Redis-tracked id (TTL {@code
 * khatm.auth.totp.challenge-ttl}, default {@code PT5M}) with no session or cookie of any kind
 * created. {@link #completeTotpChallenge} finishes the login given that id plus a TOTP code or a
 * recovery code, rate-limited by the <em>identical</em> {@code khatm:auth:fail}-shaped counter
 * mechanics as the password step (a separate key, {@code khatm:auth:totp-fail:{tenant}:{username}}
 * — scoped by tenant+username, not by challenge id, so an attacker who already knows a valid
 * password cannot dodge the counter by simply logging in again for a fresh challenge each time).
 *
 * <p>This class is module-private; the login/logout HTTP contract is {@code
 * rbac.web.AuthController}, a different Java package inside the same module — see {@link
 * LoginResult}'s Javadoc for why it (and this class) are {@code public} despite that.
 */
@Service
public class AuthService {

  private static final String LOCKOUT_KEY_PREFIX = "khatm:auth:fail:";
  private static final String TOTP_LOCKOUT_KEY_PREFIX = "khatm:auth:totp-fail:";
  private static final String TOTP_CHALLENGE_KEY_PREFIX = "khatm:auth:totp-challenge:";

  private final AppUserRepository users;
  private final RoleRepository roles;
  private final PasswordEncoder passwordEncoder;
  private final StringRedisTemplate redis;
  private final AuditService audit;
  private final TenantDirectory tenants;
  private final TotpService totpService;
  private final ObjectMapper json;
  private final int maxAttempts;
  private final Duration lockoutWindow;
  private final Duration totpChallengeTtl;

  public AuthService(
      AppUserRepository users,
      RoleRepository roles,
      PasswordEncoder passwordEncoder,
      StringRedisTemplate redis,
      AuditService audit,
      TenantDirectory tenants,
      TotpService totpService,
      ObjectMapper json,
      @Value("${khatm.auth.lockout.max-attempts:5}") int maxAttempts,
      @Value("${khatm.auth.lockout.window:15m}") Duration lockoutWindow,
      @Value("${khatm.auth.totp.challenge-ttl:PT5M}") Duration totpChallengeTtl) {
    this.users = users;
    this.roles = roles;
    this.passwordEncoder = passwordEncoder;
    this.redis = redis;
    this.audit = audit;
    this.tenants = tenants;
    this.totpService = totpService;
    this.json = json;
    this.maxAttempts = maxAttempts;
    this.lockoutWindow = lockoutWindow;
    this.totpChallengeTtl = totpChallengeTtl;
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
   * pattern (see {@code docs/CONVENTIONS.md §12}).
   *
   * @param username the submitted username
   * @param rawPassword the submitted plaintext password — never logged, never persisted
   * @param tenantSlug the tenant to authenticate against, or {@code null}/blank for the caller's
   *     ambient (default) tenant
   * @return either a completed login, or a TOTP challenge that must be completed via {@link
   *     #completeTotpChallenge} before a session exists
   * @throws AuthenticationException always with the same generic {@link ErrorCode#KH_RBC_0401}
   *     message (D7), for every failure reason — including an unknown or {@code SUSPENDED} {@code
   *     tenantSlug}, so an unauthenticated caller can never use this endpoint to probe whether a
   *     given tenant slug exists (the same unified-failure anti-enumeration stance D7 already
   *     applies to username/password)
   */
  public LoginOutcome login(String username, String rawPassword, String tenantSlug) {
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
      return authenticate(tenant.id(), tenant.slug(), username, rawPassword);
    } finally {
      TenantContext.clear();
    }
  }

  /**
   * Complete a TOTP login challenge issued by {@link #login} (spec FS-2.2 V1) with either a live
   * TOTP code or a one-time recovery code (exactly one must be non-blank).
   *
   * @param challengeId the id returned by {@link LoginOutcome.TotpChallenge}
   * @param code a live TOTP code, or {@code null}/blank if submitting a recovery code instead
   * @param recoveryCode a one-time recovery code, or {@code null}/blank if submitting a TOTP code
   * @return the completed login's session-establishing details
   * @throws ValidationException {@code KH-USR-0400} if neither or both of {@code code}/{@code
   *     recoveryCode} are provided
   * @throws AuthenticationException always with the generic {@link ErrorCode#KH_RBC_0401} for every
   *     failure reason (unknown/expired challenge, lockout, wrong code) — the identical D7
   *     anti-enumeration stance {@link #login} itself applies
   */
  public LoginResult completeTotpChallenge(String challengeId, String code, String recoveryCode) {
    boolean hasCode = code != null && !code.isBlank();
    boolean hasRecoveryCode = recoveryCode != null && !recoveryCode.isBlank();
    if (hasCode == hasRecoveryCode) {
      throw new ValidationException(
          ErrorCode.KH_USR_0400, "user.validation-failed", "totp-request");
    }

    TotpChallenge challenge = loadChallenge(challengeId).orElseThrow(AuthService::unauthenticated);
    String totpLockKey =
        TOTP_LOCKOUT_KEY_PREFIX + challenge.tenantId() + ":" + challenge.username();

    TenantContext.set(challenge.tenantId(), challenge.tenantSlug());
    try {
      if (isLockedOut(totpLockKey)) {
        audit.record(
            AuditAction.AUTH_LOCKOUT_TRIGGERED,
            "app_user",
            challenge.username(),
            Map.of("reason", "locked_temporarily_totp"));
        throw unauthenticated();
      }

      Optional<Long> recoveryCodesRemaining =
          hasRecoveryCode
              ? totpService.consumeRecoveryCode(challenge.userId(), recoveryCode)
              : Optional.empty();
      boolean verified =
          hasCode
              ? totpService.verifyLoginCode(challenge.userId(), code)
              : recoveryCodesRemaining.isPresent();

      if (!verified) {
        recordFailure(totpLockKey);
        audit.record(
            AuditAction.AUTH_LOGIN_FAILED,
            "app_user",
            challenge.username(),
            Map.of("reason", "bad_totp"));
        throw unauthenticated();
      }

      redis.delete(totpLockKey);
      redis.delete(TOTP_CHALLENGE_KEY_PREFIX + challengeId);
      LoginResult result = buildLoginResult(challenge.userId(), challenge.tenantId());
      audit.record(AuditAction.AUTH_LOGIN_SUCCESS, "app_user", challenge.username(), null);
      recoveryCodesRemaining.ifPresent(
          remaining ->
              audit.record(
                  AuditAction.USER_TOTP_RECOVERY_CODE_USED,
                  "app_user",
                  challenge.username(),
                  Map.of("remaining", remaining)));
      return result;
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

  private LoginOutcome authenticate(
      UUID tenantId, String tenantSlug, String username, String rawPassword) {
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

    if (totpService.hasActiveTotp(user.getId())) {
      String challengeId = Uuidv7.generate().toString();
      storeChallenge(challengeId, new TotpChallenge(user.getId(), tenantId, tenantSlug, username));
      return new LoginOutcome.TotpChallenge(challengeId);
    }

    audit.record(AuditAction.AUTH_LOGIN_SUCCESS, "app_user", username, null);
    return new LoginOutcome.Success(buildLoginResult(user, tenantId));
  }

  private LoginResult buildLoginResult(UUID userId, UUID tenantId) {
    AppUser user =
        users
            .findById(userId)
            .orElseThrow(() -> new IllegalStateException("User vanished mid-TOTP-challenge"));
    return buildLoginResult(user, tenantId);
  }

  private LoginResult buildLoginResult(AppUser user, UUID tenantId) {
    Set<String> scopes = new LinkedHashSet<>(roles.findScopesByUserId(user.getId()));
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
                    u.isMustChangePassword(),
                    totpService.hasActiveTotp(u.getId())));
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

  private void storeChallenge(String challengeId, TotpChallenge challenge) {
    try {
      redis
          .opsForValue()
          .set(
              TOTP_CHALLENGE_KEY_PREFIX + challengeId,
              json.writeValueAsString(challenge),
              totpChallengeTtl);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("Failed to serialize TOTP challenge", e);
    }
  }

  private Optional<TotpChallenge> loadChallenge(String challengeId) {
    if (challengeId == null || challengeId.isBlank()) {
      return Optional.empty();
    }
    String raw = redis.opsForValue().get(TOTP_CHALLENGE_KEY_PREFIX + challengeId);
    if (raw == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(json.readValue(raw, TotpChallenge.class));
    } catch (JsonProcessingException e) {
      // A stored value only this class ever writes, in one shape — corruption, not bad input.
      throw new UncheckedIOException("Failed to parse stored TOTP challenge", e);
    }
  }

  private static AuthenticationException unauthenticated() {
    return new AuthenticationException(ErrorCode.KH_RBC_0401, "error.rbc.unauthenticated");
  }

  /** The Redis-stored payload behind an issued TOTP challenge id. */
  private record TotpChallenge(UUID userId, UUID tenantId, String tenantSlug, String username) {}
}
