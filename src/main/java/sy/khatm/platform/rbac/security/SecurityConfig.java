package sy.khatm.platform.rbac.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Spring Security wiring (spec FS-0.6b §3): <b>two</b> {@link SecurityFilterChain}s, not one —
 * every request carrying a well-formed {@code Bearer khk_...} header is matched entirely by {@link
 * #apiKeySecurityFilterChain}; everything else falls through to {@link
 * #sessionSecurityFilterChain}. This split exists for a reason that only surfaced empirically: a
 * single shared chain with {@code SessionCreationPolicy.IF_REQUIRED} still runs Spring Security's
 * default {@code SessionManagementFilter}, whose session-fixation protection treats <em>any</em>
 * freshly-set, non-anonymous {@code Authentication} as "just logged in" and eagerly touches (and
 * therefore creates) an {@code HttpSession} — including one {@link ApiKeyAuthFilter} sets fresh on
 * every single request. That defeats spec §3's explicit "API key paths are stateless" requirement
 * and, worse, made every API-key-authenticated call try to persist a session. Only {@code
 * SessionCreationPolicy.STATELESS} disables that filter outright ({@code
 * NullAuthenticatedSessionStrategy}), and {@code STATELESS} cannot be scoped to "just these routes"
 * within one chain — hence two chains, matched by request shape rather than URL pattern (the same
 * endpoint, e.g. {@code /issue}, can legitimately be called either way).
 *
 * <p><b>Public endpoints (D9):</b> {@code POST /api/v1/credentials/verify} and {@code GET
 * /.well-known/jwks.json} — the only two, enforced identically on both chains via {@link
 * #configureAuthorization}. Every other endpoint requires at least a valid session or API key;
 * {@link ScopeGuard}'s per-route rules layer the specific scope/actor-kind requirement spec §3
 * names explicitly on top.
 *
 * <p><b>CSRF:</b> only {@link #sessionSecurityFilterChain} has it — {@link
 * #apiKeySecurityFilterChain} is stateless, and a bearer token is immune to CSRF by construction (a
 * browser never attaches an {@code Authorization} header to a cross-site request on its own), so
 * there is nothing to protect there. {@link CookieCsrfTokenRepository#withHttpOnlyFalse()} lets a
 * browser console (SPA) read the {@code XSRF-TOKEN} cookie and echo it back as {@code X-XSRF-TOKEN}
 * (spec §3); the plain {@link CsrfTokenRequestAttributeHandler} (not Spring Security's default
 * Xor/BREACH-protected one) is used so that read-cookie/send-header pattern needs no extra
 * encode/decode step. Exempted: {@code /verify} (genuinely public, no session ever involved) and
 * {@code /api/auth/login} (there is no session yet to protect, and a first-time caller has no token
 * to send), and — critically — any request carrying <em>no {@code KHATM_SESSION} cookie at
 * all</em>. CSRF exists to stop a forged cross-site request from riding on an ambient cookie a
 * browser attaches automatically; a request with no session cookie has no ambient credential to
 * protect, so enforcing CSRF on it only gets in the way of the real answer. Without this, {@code
 * CsrfFilter} (which runs before authentication is even resolved) rejects a completely
 * credential-less POST to, say, {@code /issue} with a bare {@code 403} — masking spec D9/DoD #3's
 * required {@code 401} for "no session, no key at all" behind an unrelated CSRF failure (confirmed
 * empirically). {@link CsrfCookieFilter} forces the {@code XSRF-TOKEN} cookie to actually be
 * written on every response — see its Javadoc for why that is not automatic for a pure JSON API in
 * Spring Security 6.
 *
 * <p><b>Worker role:</b> this configuration class loads in every profile (nothing here is
 * conditional on {@code khatm.web.enabled}) — the worker image runs no business REST endpoints
 * regardless (ADR-09's {@code @ConditionalOnProperty} on the controllers themselves), so
 * always-loaded, unused filter chains are harmless. {@code WorkerProfileSecurityBootTest} asserts
 * the worker profile still boots cleanly with Spring Security on the classpath (spec FS-0.6b DoD
 * #9).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

  private static final String VERIFY_PATH = "/api/v1/credentials/verify";
  private static final String JWKS_PATH = "/.well-known/jwks.json";
  private static final String LOGIN_PATH = "/api/auth/login";
  private static final String ISSUE_PATH = "/api/v1/credentials/issue";
  private static final String CONSUME_PATH = "/api/v1/credentials/consume";
  private static final String REVOKE_PATH = "/api/v1/credentials/*/revoke";
  private static final String ADMIN_PATH = "/api/admin/**";

  @Bean
  @Order(1)
  SecurityFilterChain apiKeySecurityFilterChain(
      HttpSecurity http,
      ApiKeyService apiKeyService,
      AuditService audit,
      KhatmAuthenticationEntryPoint entryPoint,
      KhatmAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.securityMatcher(SecurityConfig::hasApiKeyHeader)
        .authorizeHttpRequests(SecurityConfig::configureAuthorization)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(
            new ApiKeyAuthFilter(apiKeyService, audit), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain sessionSecurityFilterChain(
      HttpSecurity http,
      KhatmAuthenticationEntryPoint entryPoint,
      KhatmAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.authorizeHttpRequests(SecurityConfig::configureAuthorization)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers(
                        new AntPathRequestMatcher(VERIFY_PATH, HttpMethod.POST.name()),
                        new AntPathRequestMatcher(LOGIN_PATH, HttpMethod.POST.name()),
                        SecurityConfig::hasNoSessionCookie))
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
    return http.build();
  }

  private static void configureAuthorization(
      AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
          auth) {
    auth.requestMatchers(HttpMethod.POST, VERIFY_PATH)
        .permitAll()
        .requestMatchers(HttpMethod.GET, JWKS_PATH)
        .permitAll()
        .requestMatchers(HttpMethod.POST, LOGIN_PATH)
        .permitAll()
        .requestMatchers(HttpMethod.POST, ISSUE_PATH)
        .access(ScopeGuard.requireScopeNotConsumingPartyKey("issue"))
        .requestMatchers(HttpMethod.POST, CONSUME_PATH)
        .access(ScopeGuard.requireScopeAndConsumingPartyKey("consume"))
        .requestMatchers(HttpMethod.POST, REVOKE_PATH)
        .access(ScopeGuard.requireScopeAndUserSession("revoke"))
        .requestMatchers(ADMIN_PATH)
        .access(ScopeGuard.requireScope("admin"))
        .anyRequest()
        .authenticated();
  }

  /** Matches any request carrying a well-formed {@code Authorization: Bearer khk_...} header. */
  private static boolean hasApiKeyHeader(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    return header != null && header.startsWith("Bearer khk_");
  }

  /** True when the request carries no {@code KHATM_SESSION} cookie — see the CSRF Javadoc above. */
  private static boolean hasNoSessionCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return true;
    }
    for (Cookie cookie : cookies) {
      if ("KHATM_SESSION".equals(cookie.getName())) {
        return false;
      }
    }
    return true;
  }
}
