package sy.khatm.platform.rbac.security;

import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import sy.khatm.platform.rbac.api.CurrentActor;

/**
 * The {@link org.springframework.security.core.Authentication} implementation for every
 * authenticated request — built either by {@code SessionAuthenticator} (console login) or {@code
 * ApiKeyAuthFilter} (API key), never by Spring Security's own {@code AuthenticationManager}
 * machinery (there is no {@code AuthenticationProvider} — both paths authenticate directly against
 * {@code rbac.domain} services and construct this token on success).
 */
class KhatmAuthenticationToken extends AbstractAuthenticationToken {

  private final KhatmPrincipal principal;

  KhatmAuthenticationToken(
      CurrentActor.ActorKind kind,
      UUID id,
      UUID tenantId,
      String label,
      Set<String> scopes,
      UUID ownerId) {
    super(KhatmAuthorities.build(kind, scopes));
    this.principal = new KhatmPrincipal(kind, id, tenantId, label, ownerId);
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public Object getPrincipal() {
    return principal;
  }
}
