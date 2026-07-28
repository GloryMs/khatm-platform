package sy.khatm.platform.rbac.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
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
 * <p><b>Public endpoints (D9, extended KH-1.2.1, KH-1.3, KH-2.1, KH-1.6):</b> {@code POST
 * /api/v1/credentials/verify}, {@code GET /.well-known/jwks.json}, {@code POST
 * /api/v1/claims/redeem}, {@code GET /sl/{tenantSlug}/{listCode}}, (spec FS-2.1 D8) {@code GET
 * /t/{tenantSlug}/.well-known/jwks.json}, and (spec FS-1.6 D3) {@code POST
 * /api/v1/credentials/holder-status} — six, enforced identically on both chains via {@link
 * #configureAuthorization}. The third authenticates by possession of a one-time claim code rather
 * than a session or API key (spec FS-1.2.1 §9) — its own per-IP throttle ({@code
 * credential.domain.ClaimRedeemThrottleService}) is what keeps it from being an open door, not this
 * class. The fourth is the public signed status-list artifact (spec FS-1.3 D2) — a read-only public
 * resource exactly like JWKS, never behind auth. The fifth is the per-tenant JWKS alias (spec
 * FS-2.1 D8) — same public-by-nature reasoning as the legacy JWKS path, just slug-resolved instead
 * of hardcoded to the default tenant. The sixth authenticates by possession of the credential's own
 * bare JWT (spec FS-1.6 D3) — a deliberate, explicit reversal of PR #33's original "no live
 * uses-remaining channel" stance; every failure mode collapses to the same anti-enumeration 404,
 * the same shape {@code /verify} and claim-redeem already establish. Every other endpoint requires
 * at least a valid session or API key; {@link ScopeGuard}'s per-route rules layer the specific
 * scope/actor-kind requirement spec §3 names explicitly on top.
 *
 * <p><b>API versioning (KH-1.6-early):</b> every business and auth endpoint lives under {@code
 * /api/v1/**} — the one breaking path change this platform ever makes with a straight face, done
 * now while there are zero external clients. {@code /.well-known/jwks.json} and the springdoc paths
 * stay unversioned by convention (well-known URIs and build tooling, not business resources). From
 * here, the published contract ({@code docs/api/openapi.json}) only grows additively; a future
 * rename needs its own ADR.
 *
 * <p><b>CSRF:</b> only {@link #sessionSecurityFilterChain} has it — {@link
 * #apiKeySecurityFilterChain} is stateless, and a bearer token is immune to CSRF by construction (a
 * browser never attaches an {@code Authorization} header to a cross-site request on its own), so
 * there is nothing to protect there. {@link CookieCsrfTokenRepository#withHttpOnlyFalse()} lets a
 * browser console (SPA) read the {@code XSRF-TOKEN} cookie and echo it back as {@code X-XSRF-TOKEN}
 * (spec §3); the plain {@link CsrfTokenRequestAttributeHandler} (not Spring Security's default
 * Xor/BREACH-protected one) is used so that read-cookie/send-header pattern needs no extra
 * encode/decode step. Exempted: {@code /verify} (genuinely public, no session ever involved) and
 * {@code /api/v1/auth/login} (there is no session yet to protect, and a first-time caller has no
 * token to send), and — critically — any request carrying <em>no {@code KHATM_SESSION} cookie at
 * all</em>. CSRF exists to stop a forged cross-site request from riding on an ambient cookie a
 * browser attaches automatically; a request with no session cookie has no ambient credential to
 * protect, so enforcing CSRF on it only gets in the way of the real answer. Without this, {@code
 * CsrfFilter} (which runs before authentication is even resolved) rejects a completely
 * credential-less POST to, say, {@code /issue} with a bare {@code 403} — masking spec D9/DoD #3's
 * required {@code 401} for "no session, no key at all" behind an unrelated CSRF failure (confirmed
 * empirically). {@code /api/v1/claims/redeem} (KH-1.2.1) needs no explicit entry in this list for
 * the same reason {@code /consume} never did: a wallet calling it never carries a {@code
 * KHATM_SESSION} cookie, so the no-session-cookie exemption above already covers it. {@link
 * CsrfCookieFilter} forces the {@code XSRF-TOKEN} cookie to actually be written on every response —
 * see its Javadoc for why that is not automatic for a pure JSON API in Spring Security 6.
 *
 * <p><b>Claim-code minting (KH-1.2.2):</b> {@code POST /api/v1/credentials/{id}/claim-code} reuses
 * the exact {@link ScopeGuard#requireScopeNotConsumingPartyKey} rule {@code /issue} already has
 * (spec FS-1.2.1 D2's re-issue recovery path is issuer-side, the same actor kind as issuance itself
 * — session or TENANT API key, never a {@code CONSUMING_PARTY} key) — a deliberate, explicit
 * decision (CONVENTIONS §7.2), not the silent authenticated-any-scope default.
 *
 * <p><b>Schema read endpoints (KH-1.6-early, tightened KH-2.2a spec FS-2.2 D2/V2):</b> {@code GET
 * /api/v1/schemas} and {@code GET /api/v1/schemas/{id}} require any of {@link
 * ScopeRegistry#SCHEMA_READ_SCOPES} ({@code issue}/{@code verify}/{@code consume}/{@code
 * revoke}/{@code schema:manage}) rather than the KH-2.2a-predecessor's bare {@code authenticated()}
 * — deny-by-default (spec D1) means every gated route names its scope(s) explicitly, even one this
 * permissive. Every seeded role and every actor kind that plausibly needs to read schema metadata
 * (an issuer choosing a type, a verifier checking a definition, an authoring session) already holds
 * at least one of these, so this is not a behavior change for any real caller today.
 *
 * <p><b>Schema authoring endpoints (KH-1.1.1, re-gated KH-2.2a spec FS-2.2 D2):</b> every {@code
 * POST}/{@code PUT} under {@code /api/v1/schemas/**} (create, update, publish, version, archive)
 * requires the {@code schema:manage} scope, any actor kind. Schema <em>reads</em> ({@code GET})
 * deliberately do not require {@code schema:manage} — spec V2: an {@code ISSUER_OPERATOR} needs to
 * read schemas to choose one at issuance time without holding the authoring scope, so {@code GET}
 * requires any of {@link ScopeRegistry#SCHEMA_READ_SCOPES} instead (every actor kind that can
 * plausibly need to read schema metadata already holds one of these).
 *
 * <p><b>Credential search (KH-1.1.4):</b> {@code GET /api/v1/credentials} (list/search, distinct
 * from {@code GET /api/v1/credentials/{id}}'s single-record lookup, which stays under the generic
 * {@code anyRequest().authenticated()} fallback) requires a console session specifically — {@link
 * ScopeGuard#requireUserSession}, {@code ACTOR_USER} only, no scope, no API key of any kind. Every
 * console operator role may search/list credentials, so — like the schema read endpoints — gating
 * on a specific scope would add friction with no security benefit; unlike them, an API key caller
 * is deliberately excluded here (a search/list surface over every credential's summary data is a
 * console operator's tool, not something a {@code TENANT}/{@code CONSUMING_PARTY} integration
 * needs).
 *
 * <p><b>Bulk issuance (KH-1.1.3):</b> {@code POST /api/v1/credentials/bulk} reuses {@code /issue}'s
 * exact {@link ScopeGuard#requireScopeNotConsumingPartyKey} rule verbatim — it is the same C3
 * wizard's batch-shaped counterpart to single issuance, not a materially different operation, so it
 * gets the identical session-or-TENANT-key gate.
 *
 * <p><b>Stats/counters (KH-1.1.3):</b> {@code GET /api/v1/stats} uses the exact same rule as
 * credential search — {@link ScopeGuard#requireUserSession}, no specific scope, no API key of any
 * kind. The console's C4 pilot-metrics dashboard is an operator's tool over an aggregation of the
 * audit trail, the same "any operator role, no integration use case" judgment call credential
 * search already made.
 *
 * <p><b>Dashboard v2 (KH-1.1.5-BE, spec FS-1.5.4):</b> {@code STATS_PATH} widened from an exact
 * match to {@code /api/v1/stats/**} so {@code GET /api/v1/stats/daily} and {@code GET
 * /api/v1/stats/consuming-parties} pick up the identical rule without a separate entry each — both
 * are the same kind of read as {@code GET /api/v1/stats} itself. {@code GET /api/v1/activity} and
 * {@code GET /api/v1/attention} get their own explicit entries (they don't share the {@code
 * /api/v1/stats} path prefix) but the exact same rule: session-only, no scope, no API key of any
 * kind — the same operator-dashboard judgment call as every other endpoint in this family.
 *
 * <p><b>Admin-plane re-gate (KH-2.2a, spec FS-2.2 D1/D2/V3):</b> the coarse {@code admin} scope
 * that used to cover the entire {@code /api/v1/admin/**} wildcard as one rule is retired outright
 * (clean cut, no coexistence) and replaced by four independent, non-overlapping path families, each
 * gated on the one granular scope its spec-D2 mapping names — verified against this class's own
 * live rule set, not assumed from the D2 mapping's shape alone:
 *
 * <ul>
 *   <li>{@code ADMIN_TENANTS_PATH} ({@code /api/v1/admin/tenants/**}) — {@code platform:admin}
 *       exclusively (spec D2's own wording), the one cross-tenant plane on this platform.
 *   <li>{@code ADMIN_CONSUMING_PARTIES_PATH} ({@code /api/v1/admin/consuming-parties/**}) — {@code
 *       consumer:manage}. Covers the registry/status/allowlist endpoints in {@code consumer.web}
 *       <em>and</em> the key-mint endpoint in {@code rbac.web.ConsumingPartyKeyController} (same
 *       path prefix, one rule, per KH-1.4.4's original module-cycle rationale for where that
 *       controller lives).
 *   <li>{@code ADMIN_API_KEYS_PATH} ({@code /api/v1/admin/api-keys/**}) — {@code tenant:admin}
 *       (spec V4 — {@code key:manage} is reserved for signing keys only). {@code
 *       rbac.web.AuthController#createApiKey} additionally accepts an explicit {@code tenantId}
 *       targeting a tenant other than the caller's own (provisioning a newly onboarded tenant's
 *       first key) — that specific cross-tenant path requires {@code platform:admin} on top,
 *       enforced by {@code shared.OnBehalfOfExecutor} inside the service layer (a URL-pattern rule
 *       here cannot see the request body), not by this class. A bare {@code tenant:admin} caller
 *       may still mint/revoke keys for their own tenant via the same endpoint.
 *   <li>{@code ADMIN_SIGNING_KEYS_PATH} ({@code /api/v1/admin/signing-keys}) — {@code key:manage}.
 * </ul>
 *
 * <p><b>Tenant context (KH-2.1, spec FS-2.1 D1):</b> {@link TenantContextFilter} is wired into both
 * chains, positioned right after whichever mechanism resolves the principal ({@link
 * ApiKeyAuthFilter} on the api-key chain, {@link SecurityContextHolderFilter} on the session chain
 * — the point session-restored authentication becomes available). It populates {@code
 * shared.TenantContext} from the resolved principal's tenant for the rest of the request, and
 * closes the one suspended-tenant gap {@code ApiKeyService#verify}/{@code AuthService#login} can't
 * cover on their own: an existing session surviving its tenant being suspended mid-session (see its
 * own Javadoc).
 *
 * <p><b>Worker role:</b> this configuration class loads in every profile (nothing here is
 * conditional on {@code khatm.web.enabled}) — the worker image runs no business REST endpoints
 * regardless (ADR-09's {@code @ConditionalOnProperty} on the controllers themselves), so
 * always-loaded, unused filter chains are harmless. {@code WorkerProfileSecurityBootTest} asserts
 * the worker profile still boots cleanly with Spring Security on the classpath (spec FS-0.6b DoD
 * #9).
 *
 * <p><b>Swagger UI/api-docs (local/dev only):</b> {@code /v3/api-docs/**}, {@code /swagger-ui/**},
 * and {@code /swagger-ui.html} are permitted anonymously only when profile {@code local} or {@code
 * dev} is active ({@link Environment#matchesProfiles}) — a conditional carve-out computed per chain
 * build, not a third, permanently-public entry alongside D9's two. Outside those profiles the paths
 * fall through to {@code anyRequest().authenticated()} like everything else, so they 401 exactly as
 * an unauthenticated request to any other endpoint would.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

  private static final String VERIFY_PATH = "/api/v1/credentials/verify";
  private static final String HOLDER_STATUS_PATH = "/api/v1/credentials/holder-status";
  private static final String JWKS_PATH = "/.well-known/jwks.json";
  private static final String TENANT_JWKS_PATH = "/t/*/.well-known/jwks.json";
  private static final String STATUS_LIST_PATH = "/sl/**";
  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String ISSUE_PATH = "/api/v1/credentials/issue";
  private static final String BULK_ISSUE_PATH = "/api/v1/credentials/bulk";
  private static final String CONSUME_PATH = "/api/v1/credentials/consume";
  private static final String REVOKE_PATH = "/api/v1/credentials/*/revoke";
  private static final String CLAIM_CODE_PATH = "/api/v1/credentials/*/claim-code";
  private static final String ADMIN_TENANTS_PATH = "/api/v1/admin/tenants/**";
  private static final String ADMIN_CONSUMING_PARTIES_PATH = "/api/v1/admin/consuming-parties/**";
  private static final String ADMIN_API_KEYS_PATH = "/api/v1/admin/api-keys/**";
  private static final String ADMIN_SIGNING_KEYS_PATH = "/api/v1/admin/signing-keys";
  private static final String SCHEMAS_PATH = "/api/v1/schemas/**";
  private static final String CLAIMS_REDEEM_PATH = "/api/v1/claims/redeem";
  private static final String CREDENTIALS_LIST_PATH = "/api/v1/credentials";
  private static final String STATS_PATH = "/api/v1/stats/**";
  private static final String ACTIVITY_PATH = "/api/v1/activity";
  private static final String ATTENTION_PATH = "/api/v1/attention";
  // KH-2.2b (spec FS-2.2 D5): the tenant user-management surface. The self-service password-change
  // endpoint is declared before the wildcard so it gets the looser "any session" rule, not
  // tenant:admin — any authenticated console user may change their own password.
  private static final String USERS_PATH = "/api/v1/users/**";
  private static final String USER_PASSWORD_PATH = "/api/v1/users/me/password";
  private static final String[] SWAGGER_PATHS = {
    "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
  };

  @Bean
  @Order(1)
  SecurityFilterChain apiKeySecurityFilterChain(
      HttpSecurity http,
      ApiKeyService apiKeyService,
      AuditService audit,
      KhatmAuthenticationEntryPoint entryPoint,
      KhatmAccessDeniedHandler accessDeniedHandler,
      TenantContextFilter tenantContextFilter,
      Environment environment)
      throws Exception {
    boolean swaggerEnabled = environment.matchesProfiles("local", "dev");
    http.securityMatcher(SecurityConfig::hasApiKeyHeader)
        .authorizeHttpRequests(auth -> configureAuthorization(auth, swaggerEnabled))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(
            new ApiKeyAuthFilter(apiKeyService, audit), UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(tenantContextFilter, ApiKeyAuthFilter.class);
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain sessionSecurityFilterChain(
      HttpSecurity http,
      KhatmAuthenticationEntryPoint entryPoint,
      KhatmAccessDeniedHandler accessDeniedHandler,
      TenantContextFilter tenantContextFilter,
      PasswordChangeEnforcementFilter passwordChangeFilter,
      Environment environment)
      throws Exception {
    boolean swaggerEnabled = environment.matchesProfiles("local", "dev");
    http.authorizeHttpRequests(auth -> configureAuthorization(auth, swaggerEnabled))
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
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .addFilterAfter(tenantContextFilter, SecurityContextHolderFilter.class)
        // KH-2.2b: after the tenant context is resolved (so the live app_user read targets the
        // user's own tenant under RLS), enforce the forced-password-change gate on the session
        // chain
        // only — API keys carry no human password and are unaffected.
        .addFilterAfter(passwordChangeFilter, TenantContextFilter.class);
    return http.build();
  }

  private static void configureAuthorization(
      AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth,
      boolean swaggerEnabled) {
    if (swaggerEnabled) {
      auth.requestMatchers(SWAGGER_PATHS).permitAll();
    }
    auth.requestMatchers(HttpMethod.POST, VERIFY_PATH)
        .permitAll()
        .requestMatchers(HttpMethod.POST, HOLDER_STATUS_PATH)
        .permitAll()
        .requestMatchers(HttpMethod.GET, JWKS_PATH)
        .permitAll()
        .requestMatchers(HttpMethod.GET, TENANT_JWKS_PATH)
        .permitAll()
        .requestMatchers(HttpMethod.GET, STATUS_LIST_PATH)
        .permitAll()
        .requestMatchers(HttpMethod.POST, LOGIN_PATH)
        .permitAll()
        .requestMatchers(HttpMethod.POST, CLAIMS_REDEEM_PATH)
        .permitAll()
        .requestMatchers(HttpMethod.POST, ISSUE_PATH)
        .access(ScopeGuard.requireScopeNotConsumingPartyKey(ScopeRegistry.ISSUE))
        .requestMatchers(HttpMethod.POST, BULK_ISSUE_PATH)
        .access(ScopeGuard.requireScopeNotConsumingPartyKey(ScopeRegistry.ISSUE))
        .requestMatchers(HttpMethod.POST, CLAIM_CODE_PATH)
        .access(ScopeGuard.requireScopeNotConsumingPartyKey(ScopeRegistry.ISSUE))
        .requestMatchers(HttpMethod.POST, CONSUME_PATH)
        .access(ScopeGuard.requireScopeAndConsumingPartyKey(ScopeRegistry.CONSUME))
        .requestMatchers(HttpMethod.POST, REVOKE_PATH)
        .access(ScopeGuard.requireScopeAndUserSession(ScopeRegistry.REVOKE))
        .requestMatchers(ADMIN_TENANTS_PATH)
        .access(ScopeGuard.requireScope(ScopeRegistry.PLATFORM_ADMIN))
        .requestMatchers(ADMIN_CONSUMING_PARTIES_PATH)
        .access(ScopeGuard.requireScope(ScopeRegistry.CONSUMER_MANAGE))
        .requestMatchers(ADMIN_API_KEYS_PATH)
        .access(
            ScopeGuard.requireAnyScope(
                Set.of(ScopeRegistry.TENANT_ADMIN, ScopeRegistry.PLATFORM_ADMIN)))
        .requestMatchers(HttpMethod.GET, ADMIN_SIGNING_KEYS_PATH)
        .access(ScopeGuard.requireScope(ScopeRegistry.KEY_MANAGE))
        .requestMatchers(HttpMethod.GET, SCHEMAS_PATH)
        .access(ScopeGuard.requireAnyScope(ScopeRegistry.SCHEMA_READ_SCOPES))
        .requestMatchers(HttpMethod.POST, SCHEMAS_PATH)
        .access(ScopeGuard.requireScope(ScopeRegistry.SCHEMA_MANAGE))
        .requestMatchers(HttpMethod.PUT, SCHEMAS_PATH)
        .access(ScopeGuard.requireScope(ScopeRegistry.SCHEMA_MANAGE))
        .requestMatchers(HttpMethod.GET, CREDENTIALS_LIST_PATH)
        .access(ScopeGuard.requireUserSession())
        .requestMatchers(HttpMethod.GET, STATS_PATH)
        .access(ScopeGuard.requireUserSession())
        .requestMatchers(HttpMethod.GET, ACTIVITY_PATH)
        .access(ScopeGuard.requireUserSession())
        .requestMatchers(HttpMethod.GET, ATTENTION_PATH)
        .access(ScopeGuard.requireUserSession())
        // KH-2.2b (spec FS-2.2 D5): tenant user management. The self-service change-password
        // endpoint
        // is matched first and needs only a console session (any operator changes their own
        // password); every other /api/v1/users/** path requires the tenant:admin scope
        // specifically,
        // as a console session (ACTOR_USER), never an API key.
        .requestMatchers(HttpMethod.POST, USER_PASSWORD_PATH)
        .access(ScopeGuard.requireUserSession())
        .requestMatchers(USERS_PATH)
        .access(ScopeGuard.requireScopeAndUserSession(ScopeRegistry.TENANT_ADMIN))
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
