package sy.khatm.platform.rbac.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import sy.khatm.platform.issuerclient.api.IssuerClientAuthenticator;
import sy.khatm.platform.issuerclient.api.IssuerClientPrincipal;
import sy.khatm.platform.rbac.api.CurrentActor;

/**
 * Authenticates {@code Authorization: Bearer khi_...} requests — machine-to-machine issuer clients
 * (KH-2.8.1, spec FS-2.7a D2/D3). The adjacent twin of {@link ApiKeyAuthFilter}, on the same
 * stateless chain.
 *
 * <p>Lives in {@code rbac.security} because {@link KhatmAuthenticationToken} is package-private
 * here; the key verification itself is entirely {@code issuerclient}'s ({@link
 * IssuerClientAuthenticator}). On success it populates the {@code SecurityContext} for this request
 * only — no session is ever created — with kind {@link
 * CurrentActor.ActorKind#API_KEY_ISSUER_CLIENT} and the tenant read from the {@code issuer_client}
 * row; {@link TenantContextFilter} then sets the tenant context from it, so {@code app.tenant_id}
 * can never come from anything the caller sends.
 *
 * <p>On failure it does <b>not</b> reject: it leaves the context unauthenticated and lets {@code
 * SecurityConfig}'s entry point produce the single generic {@code KH-AUTH-0401} — every failure
 * flavor is indistinguishable from outside (D12). The audit row is written by the authenticator.
 */
class IssuerClientAuthFilter extends OncePerRequestFilter {

  private static final String AUTH_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String KEY_TAG = "khi_";

  private final IssuerClientAuthenticator authenticator;

  IssuerClientAuthFilter(IssuerClientAuthenticator authenticator) {
    this.authenticator = authenticator;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Optional<String> rawKey = extractRawKey(request);
    if (rawKey.isPresent()) {
      authenticator.resolve(rawKey.get()).ifPresent(IssuerClientAuthFilter::authenticate);
    }
    filterChain.doFilter(request, response);
  }

  private static void authenticate(IssuerClientPrincipal principal) {
    KhatmAuthenticationToken token =
        new KhatmAuthenticationToken(
            CurrentActor.ActorKind.API_KEY_ISSUER_CLIENT,
            principal.clientId(),
            principal.tenantId(),
            principal.clientId().toString(),
            principal.scopes(),
            null);
    SecurityContextHolder.getContext().setAuthentication(token);
  }

  private static Optional<String> extractRawKey(HttpServletRequest request) {
    String header = request.getHeader(AUTH_HEADER);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return Optional.empty();
    }
    String value = header.substring(BEARER_PREFIX.length());
    return value.startsWith(KEY_TAG) ? Optional.of(value) : Optional.empty();
  }
}
