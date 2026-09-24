package sy.khatm.platform.rbac.security;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * {@link AuthorizationManager} factories for {@code SecurityConfig}'s per-route rules (spec FS-0.6b
 * §3) — each combines a required {@code SCOPE_*} authority with, where the spec requires it, a
 * required or forbidden {@code ACTOR_*} authority (see {@link KhatmAuthorities}):
 *
 * <ul>
 *   <li>{@code /issue} — {@code issue} scope, any actor <em>except</em> a {@code CONSUMING_PARTY}
 *       key (spec §3: "جلسة أو مفتاح TENANT").
 *   <li>{@code /revoke} — {@code revoke} scope, {@code ACTOR_USER} only (spec §3: "جلسة").
 *   <li>{@code /consume} — {@code consume} scope, {@code ACTOR_API_KEY_CONSUMING_PARTY} only (SEC
 *       §7 — a console session is explicitly {@code 403} here, DoD #4).
 *   <li>{@code /api/v1/admin/**} — one of the granular scopes in {@link ScopeRegistry} per family
 *       (spec FS-2.2 D2), any actor; the coarse {@code admin} scope is retired (spec V3).
 * </ul>
 *
 * <p>Every decision is a plain boolean over the current {@link Authentication}'s authorities — an
 * anonymous request (no session, no key) fails every check here exactly like a wrong one, and
 * {@code ExceptionTranslationFilter}'s standard anonymous-vs-authenticated distinction is what
 * turns that into a {@code 401} (via {@code KhatmAuthenticationEntryPoint}) instead of a {@code
 * 403} (via {@code KhatmAccessDeniedHandler}) — no code here needs to special-case "not logged in
 * at all" itself.
 */
final class ScopeGuard {

  private ScopeGuard() {}

  static AuthorizationManager<RequestAuthorizationContext> requireScope(String scope) {
    return (authentication, context) -> decide(hasAuthority(authentication, scopeAuthority(scope)));
  }

  /**
   * Grants access to any actor holding <em>at least one</em> of {@code scopes} — used where several
   * distinct capabilities all legitimately need the same read (spec FS-2.2 D2/V2: schema read
   * endpoints accept any action scope or {@code schema:manage}, not just one specific scope).
   */
  static AuthorizationManager<RequestAuthorizationContext> requireAnyScope(Set<String> scopes) {
    return (authentication, context) ->
        decide(
            scopes.stream().anyMatch(scope -> hasAuthority(authentication, scopeAuthority(scope))));
  }

  static AuthorizationManager<RequestAuthorizationContext> requireScopeNotConsumingPartyKey(
      String scope) {
    return (authentication, context) ->
        decide(
            hasAuthority(authentication, scopeAuthority(scope))
                && !hasAuthority(authentication, KhatmAuthorities.ACTOR_API_KEY_CONSUMING_PARTY));
  }

  static AuthorizationManager<RequestAuthorizationContext> requireScopeAndUserSession(
      String scope) {
    return (authentication, context) ->
        decide(
            hasAuthority(authentication, scopeAuthority(scope))
                && hasAuthority(authentication, KhatmAuthorities.ACTOR_USER));
  }

  static AuthorizationManager<RequestAuthorizationContext> requireScopeAndConsumingPartyKey(
      String scope) {
    return (authentication, context) ->
        decide(
            hasAuthority(authentication, scopeAuthority(scope))
                && hasAuthority(authentication, KhatmAuthorities.ACTOR_API_KEY_CONSUMING_PARTY));
  }

  /**
   * A console session, any scope — no API key of any kind (KH-1.1.4, {@code GET
   * /api/v1/credentials}'s "session-authenticated, any operator" requirement). Unlike {@link
   * #requireScopeAndUserSession}, this couples no specific scope to the actor-kind check: every
   * console operator role may search/list credentials, so gating on a scope here would add friction
   * with no security benefit, the same judgment call the schema read endpoints already made for
   * their own "any authenticated actor" rule.
   */
  static AuthorizationManager<RequestAuthorizationContext> requireUserSession() {
    return (authentication, context) ->
        decide(hasAuthority(authentication, KhatmAuthorities.ACTOR_USER));
  }

  /**
   * The routes an issuer-client principal ({@code Bearer khi_...}, spec FS-2.7a D4) may call —
   * everything else is denied. Single-issue, bulk issue, and reading one credential by id (the
   * ownership check for that last one lives in {@code CredentialService#getView}: a URL rule cannot
   * see whose credential it is).
   */
  private static final List<RequestMatcher> ISSUER_CLIENT_ALLOWED =
      List.of(
          new RegexRequestMatcher("^/api/v1/credentials/issue$", "POST"),
          new RegexRequestMatcher("^/api/v1/credentials/bulk$", "POST"),
          new RegexRequestMatcher(
              "^/api/v1/credentials/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                  + "-[0-9a-fA-F]{12}$",
              "GET"));

  /** Whether the current request was authenticated as an issuer client. */
  static boolean isIssuerClientAuthenticated() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      return false;
    }
    for (GrantedAuthority granted : authentication.getAuthorities()) {
      if (KhatmAuthorities.ACTOR_API_KEY_ISSUER_CLIENT.equals(granted.getAuthority())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The ONE central rule for issuer-client principals (spec FS-2.7a D4, deny-by-default): granted
   * only for the {@code issue} scope on an allowlisted route. Registered first in {@code
   * SecurityConfig}, matched by actor kind, so it overrides every per-path rule below it — without
   * it, e.g. {@code GET /api/v1/schemas} (which accepts any action scope, {@code issue} included)
   * would leak to a machine principal. A future route is therefore denied to issuer clients until
   * someone adds it here on purpose.
   */
  static AuthorizationManager<RequestAuthorizationContext> requireIssuerClientAllowedRoute() {
    return (authentication, context) ->
        decide(
            hasAuthority(authentication, scopeAuthority(ScopeRegistry.ISSUE))
                && ISSUER_CLIENT_ALLOWED.stream()
                    .anyMatch(matcher -> matcher.matches(context.getRequest())));
  }

  private static String scopeAuthority(String scope) {
    return "SCOPE_" + scope;
  }

  private static boolean hasAuthority(
      Supplier<Authentication> authenticationSupplier, String authority) {
    Authentication authentication = authenticationSupplier.get();
    if (authentication == null) {
      return false;
    }
    for (GrantedAuthority granted : authentication.getAuthorities()) {
      if (authority.equals(granted.getAuthority())) {
        return true;
      }
    }
    return false;
  }

  private static AuthorizationDecision decide(boolean granted) {
    return new AuthorizationDecision(granted);
  }
}
