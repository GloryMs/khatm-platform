package sy.khatm.platform.rbac.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

/**
 * Populates {@link TenantContext} for the remainder of an authenticated request, resolved strictly
 * from the authenticated principal — never from a request body, header, or query parameter (spec
 * FS-2.1 D1: "Tenant context resolved from authenticated principal only").
 *
 * <p>Runs after authentication is resolved on both {@code SecurityConfig} filter chains (after
 * {@link ApiKeyAuthFilter} on the api-key chain, after session restoration on the session chain).
 * An anonymous request (no {@link KhatmPrincipal} in the {@link SecurityContextHolder} — {@code
 * /verify}, {@code /api/v1/claims/redeem}, {@code /sl/**}, JWKS) passes through untouched; {@link
 * TenantContext} stays at its default fallback, exactly as it should for these deliberately
 * un-tenant-scoped-by-principal public paths.
 *
 * <p><b>D7 — suspended tenant, session gap:</b> {@code rbac.domain.ApiKeyService#verify} and {@code
 * rbac.domain.AuthService#login} already reject a suspended tenant's principal (mirroring
 * KH-1.4.4's suspended-consuming-party check) — but a console session established <em>before</em>
 * its tenant was suspended survives independently of those two checks (Spring Session restores it
 * from Redis without re-running either). This filter closes that gap: if the resolved tenant is
 * {@code SUSPENDED}, {@link SecurityContextHolder#clearContext()} makes the rest of the chain treat
 * the request as anonymous, producing the same 401 as "no session, no key at all" — the same
 * failure shape D7 asks for, without inventing a second error path.
 *
 * <p><b>Always clears {@link TenantContext} in a {@code finally} block</b> — servlet containers
 * reuse worker threads across unrelated requests, so a value left set would leak into the next one.
 */
@Component
class TenantContextFilter extends OncePerRequestFilter {

  private final TenantDirectory tenants;

  TenantContextFilter(TenantDirectory tenants) {
    this.tenants = tenants;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    KhatmPrincipal principal = currentPrincipal();
    if (principal == null) {
      filterChain.doFilter(request, response);
      return;
    }

    TenantRef tenant = tenants.findById(principal.tenantId()).orElse(null);
    if (tenant == null || !tenant.isActive()) {
      SecurityContextHolder.clearContext();
      filterChain.doFilter(request, response);
      return;
    }

    try {
      TenantContext.set(tenant.id(), tenant.slug());
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }

  private static KhatmPrincipal currentPrincipal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof KhatmPrincipal principal) {
      return principal;
    }
    return null;
  }
}
