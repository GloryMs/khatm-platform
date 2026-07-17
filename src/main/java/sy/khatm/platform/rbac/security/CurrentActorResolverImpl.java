package sy.khatm.platform.rbac.security;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import sy.khatm.platform.rbac.api.CurrentActor;
import sy.khatm.platform.rbac.api.CurrentActorResolver;

/**
 * Default {@link CurrentActorResolver}, reading {@link SecurityContextHolder} (spec FS-0.6b §3).
 */
@Component
class CurrentActorResolverImpl implements CurrentActorResolver {

  private static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

  @Override
  public Optional<CurrentActor> resolve() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !(authentication.getPrincipal() instanceof KhatmPrincipal principal)) {
      return Optional.empty();
    }
    Set<String> scopes = new LinkedHashSet<>();
    for (GrantedAuthority authority : authentication.getAuthorities()) {
      String value = authority.getAuthority();
      if (value.startsWith(SCOPE_AUTHORITY_PREFIX)) {
        scopes.add(value.substring(SCOPE_AUTHORITY_PREFIX.length()));
      }
    }
    return Optional.of(
        new CurrentActor(principal.kind(), principal.id(), principal.tenantId(), scopes));
  }
}
