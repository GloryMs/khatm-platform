package sy.khatm.platform.rbac.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import sy.khatm.platform.rbac.api.CurrentActor;
import sy.khatm.platform.rbac.domain.LoginResult;

/**
 * Establishes and tears down the {@code KHATM_SESSION} cookie session around a successful/ended
 * console login (spec FS-0.6b D1).
 *
 * <p>{@code rbac.domain.AuthService} stays framework-agnostic (spec FS-0.6b's domain layer has no
 * Spring Security dependency) — this class is the one place a successful {@link LoginResult}
 * becomes an actual {@link org.springframework.security.core.Authentication}, explicitly persisted
 * to the {@code HttpSession} via {@link HttpSessionSecurityContextRepository} (Spring Security 6's
 * {@code SecurityContextHolderFilter} only <em>loads</em> the context automatically on later
 * requests — nothing persists it unless something calls {@code saveContext} itself, which is
 * exactly why {@code ApiKeyAuthFilter}'s per-request context never leaks into a session: it never
 * calls this).
 *
 * <p>Public — {@code rbac.web.AuthController} (a different Java package inside the module-private
 * {@code rbac} module) is the only caller; same visibility precedent as {@code
 * key.domain.KeyLifecycleService} (spec FS-0.5 D-notes).
 */
@Component
public class SessionAuthenticator {

  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  /**
   * Establish a real, session-backed login for a just-authenticated console user.
   *
   * @param request the current request (needed to create/attach the {@code HttpSession})
   * @param response the current response (the session cookie is written here)
   * @param result the successful {@code AuthService#login} outcome to build the session around
   */
  public void establish(
      HttpServletRequest request, HttpServletResponse response, LoginResult result) {
    KhatmAuthenticationToken token =
        new KhatmAuthenticationToken(
            CurrentActor.ActorKind.USER,
            result.userId(),
            result.tenantId(),
            result.username(),
            result.scopes(),
            null);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(token);
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, request, response);
  }

  /**
   * Invalidate the current session and clear the security context (logout).
   *
   * @param request the current request
   * @param response the current response
   */
  public void clear(HttpServletRequest request, HttpServletResponse response) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    new SecurityContextLogoutHandler().logout(request, response, authentication);
  }
}
