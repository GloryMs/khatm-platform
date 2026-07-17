package sy.khatm.platform.rbac.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forces the {@code XSRF-TOKEN} cookie to actually be written (spec FS-0.6b §3).
 *
 * <p>Spring Security 6's {@code CsrfFilter} resolves the current {@link CsrfToken} <em>lazily</em>
 * — it stores a deferred, {@code Supplier}-backed token as a request attribute, and {@code
 * CookieCsrfTokenRepository} only actually writes the cookie when something calls {@link
 * CsrfToken#getToken()} on it. For a pure JSON REST backend with no server-rendered view ever
 * reading {@code ${_csrf.token}}, nothing does that by default — the console (SPA) would never
 * receive a cookie to read in the first place (spec §3: "الكونسول يقرأ الكوكي ويرسل X-XSRF-TOKEN").
 * This filter runs immediately after {@code CsrfFilter} and simply forces that resolution on every
 * request, restoring the eager-cookie behavior a browser client needs — the same workaround Spring
 * Security's own reference documentation describes for SPA backends.
 */
class CsrfCookieFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Object token = request.getAttribute(CsrfToken.class.getName());
    if (token instanceof CsrfToken csrfToken) {
      csrfToken.getToken();
    }
    filterChain.doFilter(request, response);
  }
}
