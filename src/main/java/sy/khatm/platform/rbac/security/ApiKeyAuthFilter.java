package sy.khatm.platform.rbac.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.VerifiedApiKey;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Authenticates {@code Authorization: Bearer khk_...} requests (spec FS-0.6b D2/D3).
 *
 * <p>Runs on every request, but only acts on ones carrying that specific header shape — everything
 * else passes through unauthenticated, leaving session-cookie authentication (already established
 * earlier in the chain, if any) untouched. On success, populates {@link
 * org.springframework.security.core.context.SecurityContextHolder} for the remainder of this
 * request only: nothing here ever calls a {@code SecurityContextRepository} to persist it, so an
 * API-key-authenticated request never creates or touches an {@code HttpSession} (spec FS-0.6b §3 —
 * "مسارات مفاتيح الـ API stateless").
 *
 * <p>On failure (malformed, unknown, revoked, or wrong secret), this filter does <b>not</b> reject
 * the request itself — it simply leaves the context unauthenticated and lets {@code
 * SecurityConfig}'s {@code authorizeHttpRequests}/{@code ExceptionTranslationFilter} produce the
 * actual {@code 401}, so a bad key presented to a public endpoint ({@code /verify}, the JWKS
 * endpoint) never blocks that request (D9). It does record {@code API_KEY_AUTH_FAILED} — spec
 * FS-0.6b §6 attributes this action to this filter specifically, with only the best-effort parsed
 * {@code prefix} in {@code detail}, never the secret.
 */
class ApiKeyAuthFilter extends OncePerRequestFilter {

  private static final String AUTH_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String KEY_TAG = "khk_";

  private final ApiKeyService apiKeyService;
  private final AuditService audit;

  ApiKeyAuthFilter(ApiKeyService apiKeyService, AuditService audit) {
    this.apiKeyService = apiKeyService;
    this.audit = audit;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Optional<String> rawKey = extractRawKey(request);
    if (rawKey.isPresent()) {
      Optional<VerifiedApiKey> verified = apiKeyService.verify(rawKey.get());
      if (verified.isPresent()) {
        authenticate(verified.get());
      } else {
        String prefix = ApiKeyService.extractPrefixForLogging(rawKey.get()).orElse("unknown");
        audit.record(AuditAction.API_KEY_AUTH_FAILED, "api_key", prefix, Map.of("prefix", prefix));
      }
    }
    filterChain.doFilter(request, response);
  }

  private void authenticate(VerifiedApiKey verified) {
    KhatmAuthenticationToken token =
        new KhatmAuthenticationToken(
            verified.kind(),
            verified.apiKeyId(),
            verified.tenantId(),
            verified.apiKeyId().toString(),
            verified.scopes());
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
