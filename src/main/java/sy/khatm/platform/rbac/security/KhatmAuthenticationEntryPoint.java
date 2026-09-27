package sy.khatm.platform.rbac.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.error.ErrorCode;

/**
 * Writes the {@code 401} envelope for any request {@code SecurityConfig}'s {@code
 * authorizeHttpRequests} rejects with no usable authentication at all (spec FS-0.6b §5).
 *
 * <p>A {@code Bearer khi_...} attempt (issuer client, KH-2.8.1) always gets {@link
 * ErrorCode#KH_AUTH_0401}.
 *
 * <p>Distinguishes the two {@code 401} codes by whether the request even attempted an API key: a
 * request carrying a {@code Bearer khk_...} header that {@code ApiKeyAuthFilter} could not
 * authenticate gets {@link ErrorCode#KH_RBC_1401} (a specific key was wrong); every other
 * unauthenticated request (no session, no key at all) gets {@link ErrorCode#KH_RBC_0401} — the same
 * generic code a failed console login uses (D7).
 */
@Component
class KhatmAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private static final String AUTH_HEADER = "Authorization";
  private static final String API_KEY_ATTEMPT_PREFIX = "Bearer khk_";
  private static final String ISSUER_CLIENT_ATTEMPT_PREFIX = "Bearer khi_";

  private final SecurityEnvelopeWriter envelopeWriter;

  KhatmAuthenticationEntryPoint(SecurityEnvelopeWriter envelopeWriter) {
    this.envelopeWriter = envelopeWriter;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    String header = request.getHeader(AUTH_HEADER);
    if (header != null && header.startsWith(ISSUER_CLIENT_ATTEMPT_PREFIX)) {
      // Spec FS-2.7a D12: one external body for every issuer-client failure flavor (unknown,
      // wrong secret, suspended, revoked, expired, retired) — the reason lives only in the audit.
      envelopeWriter.write(request, response, ErrorCode.KH_AUTH_0401);
      return;
    }
    boolean apiKeyAttempted = header != null && header.startsWith(API_KEY_ATTEMPT_PREFIX);
    envelopeWriter.write(
        request, response, apiKeyAttempted ? ErrorCode.KH_RBC_1401 : ErrorCode.KH_RBC_0401);
  }
}
