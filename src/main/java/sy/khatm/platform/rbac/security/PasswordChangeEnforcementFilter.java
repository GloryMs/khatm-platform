package sy.khatm.platform.rbac.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import sy.khatm.platform.rbac.api.CurrentActor;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.shared.error.ErrorCode;

/**
 * Enforces the forced-password-change gate (spec FS-2.2 D5): a console user whose {@code
 * must_change_password} flag is set (a temporary password from create / reset-password /
 * onboarding's first admin) may call only the self-service change endpoint, logout, and the
 * genuinely-public paths — every other authenticated call is rejected with {@code 403 KH-USR-0403}
 * so the console can route to the change screen on that distinct code rather than a generic
 * missing-scope 403.
 *
 * <p><b>Read live, per request:</b> the flag is read straight from {@code app_user} on every
 * request, never cached in the session principal. The principal is baked at login (the codebase's
 * accepted staleness window for scopes), but {@code must_change_password} can flip to {@code true}
 * <em>mid-session</em> — an administrator resetting another user's password — and the spec's
 * guarantee is that the user's <em>very next</em> call is blocked. Only a live read satisfies that;
 * the cost is one PK-indexed scalar query per authenticated session request, acceptable for a
 * low-volume console (the same trade-off {@code ApiKeyService#verify} makes on every API-key
 * request, for the identical "must hold even if state changed since login" reason).
 *
 * <p><b>Exemption list:</b> the self-service change endpoint, logout, and the platform's public
 * paths (the {@code permitAll} set declared in {@link SecurityConfig} — a logged-in user reaching
 * one of those, rare, is not blocked; everything a temporary-password user could legitimately need
 * to reach is either here or has no authenticated principal at this layer at all). Enumerated and
 * pinned by {@code rbac.security.PasswordChangeEnforcementFilterExemptionTest}.
 *
 * <p>Wired into the session chain only ({@link SecurityConfig#sessionSecurityFilterChain}), after
 * {@link TenantContextFilter} — the live {@code app_user} read needs the tenant context already
 * resolved, both for Row-Level Security and so the read targets the user's own tenant.
 */
@Component
class PasswordChangeEnforcementFilter extends OncePerRequestFilter {

  private final AppUserRepository users;
  private final SecurityEnvelopeWriter envelopeWriter;

  PasswordChangeEnforcementFilter(AppUserRepository users, SecurityEnvelopeWriter envelopeWriter) {
    this.users = users;
    this.envelopeWriter = envelopeWriter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    KhatmPrincipal principal = currentPrincipal();
    if (principal != null
        && principal.kind() == CurrentActor.ActorKind.USER
        && !isExempt(request)
        && users.findMustChangePasswordById(principal.id()).orElse(false)) {
      envelopeWriter.write(request, response, ErrorCode.KH_USR_0403);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static boolean isExempt(HttpServletRequest request) {
    for (RequestMatcher matcher : EXEMPT) {
      if (matcher.matches(request)) {
        return true;
      }
    }
    return false;
  }

  private static KhatmPrincipal currentPrincipal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof KhatmPrincipal principal) {
      return principal;
    }
    return null;
  }

  // Mirrors SecurityConfig's permitAll public-path set (spec FS-0.6b D9 + the
  // KH-1.2.1/KH-1.3/KH-2.1
  // /KH-1.6 extensions) plus the two paths a temporary-password user needs to escape or satisfy the
  // gate. Keep in lockstep with SecurityConfig's public endpoints.
  private static final List<RequestMatcher> EXEMPT =
      List.of(
          new AntPathRequestMatcher("/api/v1/users/me/password", "POST"),
          new AntPathRequestMatcher("/api/v1/auth/logout", "POST"),
          new AntPathRequestMatcher("/api/v1/auth/login", "POST"),
          new AntPathRequestMatcher("/api/v1/credentials/verify", "POST"),
          new AntPathRequestMatcher("/api/v1/credentials/holder-status", "POST"),
          new AntPathRequestMatcher("/api/v1/claims/redeem", "POST"),
          new AntPathRequestMatcher("/.well-known/jwks.json", "GET"),
          new AntPathRequestMatcher("/t/*/.well-known/jwks.json", "GET"),
          new AntPathRequestMatcher("/sl/**", "GET"));
}
