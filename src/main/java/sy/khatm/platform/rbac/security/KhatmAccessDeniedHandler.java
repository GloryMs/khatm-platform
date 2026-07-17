package sy.khatm.platform.rbac.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.error.ErrorCode;

/**
 * Writes the {@code 403} envelope ({@link ErrorCode#KH_RBC_0403}) for a request whose session or
 * API key is valid but lacks the scope/actor-kind {@code SecurityConfig}'s {@code
 * authorizeHttpRequests} rule requires (spec FS-0.6b §5) — e.g. an operator session without the
 * {@code issue} scope, or a console session calling {@code /consume} (SEC §7, DoD #4).
 */
@Component
class KhatmAccessDeniedHandler implements AccessDeniedHandler {

  private final SecurityEnvelopeWriter envelopeWriter;

  KhatmAccessDeniedHandler(SecurityEnvelopeWriter envelopeWriter) {
    this.envelopeWriter = envelopeWriter;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    envelopeWriter.write(request, response, ErrorCode.KH_RBC_0403);
  }
}
