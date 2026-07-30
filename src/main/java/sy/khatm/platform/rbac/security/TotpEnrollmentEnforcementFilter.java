package sy.khatm.platform.rbac.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import sy.khatm.platform.rbac.api.CurrentActor;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.shared.error.ErrorCode;

/**
 * Enforces mandatory TOTP enrollment (spec FS-2.2 V1): a console user holding any of {@code
 * revoke}/{@code tenant:admin}/{@code platform:admin}/{@code key:manage} but with no active
 * (confirmed) TOTP enrollment may call only the enroll/confirm endpoints, {@code GET
 * /api/v1/auth/me}, logout, and the platform's genuinely-public paths — every other authenticated
 * call is rejected with {@code 403 KH-USR-1403} so the console can route to the enrollment screen
 * on that distinct code rather than a generic missing-scope 403.
 *
 * <p>Mirrors {@code PasswordChangeEnforcementFilter}'s exact shape (per the session brief — forced
 * enrollment reuses this pattern, not a sibling invented from scratch): same live-per-request-read
 * rationale (an administrator's {@code POST /users/{id}/totp/reset} must bite on the target's very
 * next request even mid-session, so {@code totp_confirmed_at} is never cached in the session
 * principal), same exemption-list shape, same {@code SecurityEnvelopeWriter} response mechanism.
 * The <em>mandatory-scope</em> check, unlike the TOTP-confirmed check, reads the session's already-
 * baked-in {@code SCOPE_*} authorities directly — consistent with every other {@code ScopeGuard}
 * rule in this codebase, which also accepts the login-time staleness window for scopes rather than
 * re-reading them from the database on every request.
 *
 * <p>Wired into the session chain only ({@link SecurityConfig#sessionSecurityFilterChain}), after
 * {@link PasswordChangeEnforcementFilter} — a temporary-password user must clear that gate first;
 * only once a real password is set does the TOTP-enrollment gate become the next thing in the way.
 * API keys carry no human TOTP either and are unaffected (session chain only).
 */
@Component
class TotpEnrollmentEnforcementFilter extends OncePerRequestFilter {

  private static final Set<String> MANDATORY_SCOPES =
      Set.of("revoke", "tenant:admin", "platform:admin", "key:manage");

  private final AppUserRepository users;
  private final SecurityEnvelopeWriter envelopeWriter;

  TotpEnrollmentEnforcementFilter(AppUserRepository users, SecurityEnvelopeWriter envelopeWriter) {
    this.users = users;
    this.envelopeWriter = envelopeWriter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    KhatmPrincipal principal = principalOf(authentication);
    if (principal != null
        && principal.kind() == CurrentActor.ActorKind.USER
        && !isExempt(request)
        && requiresTotp(authentication)
        && !users.findHasActiveTotpById(principal.id()).orElse(false)) {
      envelopeWriter.write(request, response, ErrorCode.KH_USR_1403);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static boolean requiresTotp(Authentication authentication) {
    for (GrantedAuthority authority : authentication.getAuthorities()) {
      String value = authority.getAuthority();
      if (value.startsWith("SCOPE_") && MANDATORY_SCOPES.contains(value.substring(6))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isExempt(HttpServletRequest request) {
    for (RequestMatcher matcher : EXEMPT) {
      if (matcher.matches(request)) {
        return true;
      }
    }
    return false;
  }

  private static KhatmPrincipal principalOf(Authentication authentication) {
    if (authentication != null
        && authentication.getPrincipal() instanceof KhatmPrincipal principal) {
      return principal;
    }
    return null;
  }

  // Mirrors PasswordChangeEnforcementFilter's exemption list, swapping the password-change escape
  // route for the TOTP enroll/confirm ones. Keep in lockstep with SecurityConfig's public paths.
  private static final List<RequestMatcher> EXEMPT =
      List.of(
          new AntPathRequestMatcher("/api/v1/users/me/totp/enroll", "POST"),
          new AntPathRequestMatcher("/api/v1/users/me/totp/confirm", "POST"),
          // A user who still must change their temporary password (PasswordChangeEnforcementFilter,
          // which runs first) needs this reachable regardless of TOTP status — password change is
          // step one, TOTP enrollment is step two, never the other way around.
          new AntPathRequestMatcher("/api/v1/users/me/password", "POST"),
          new AntPathRequestMatcher("/api/v1/auth/me", "GET"),
          new AntPathRequestMatcher("/api/v1/auth/logout", "POST"),
          new AntPathRequestMatcher("/api/v1/auth/login", "POST"),
          new AntPathRequestMatcher("/api/v1/credentials/verify", "POST"),
          new AntPathRequestMatcher("/api/v1/credentials/holder-status", "POST"),
          new AntPathRequestMatcher("/api/v1/claims/redeem", "POST"),
          new AntPathRequestMatcher("/.well-known/jwks.json", "GET"),
          new AntPathRequestMatcher("/t/*/.well-known/jwks.json", "GET"),
          new AntPathRequestMatcher("/sl/**", "GET"));
}
