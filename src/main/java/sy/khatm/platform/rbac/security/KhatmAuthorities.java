package sy.khatm.platform.rbac.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import sy.khatm.platform.rbac.api.CurrentActor;

/**
 * Builds the {@link GrantedAuthority} set every {@link KhatmAuthenticationToken} carries (spec
 * FS-0.6b §3).
 *
 * <p>Two authority families, checked together by {@link ScopeGuard}:
 *
 * <ul>
 *   <li>{@code SCOPE_<scope>} — one per granted scope (a user's roles' union, or an API key's own
 *       {@code scopes} column), e.g. {@code SCOPE_issue}.
 *   <li>{@code ACTOR_<kind>} — exactly one, identifying <em>what kind</em> of principal this is
 *       ({@code ACTOR_USER}, {@code ACTOR_API_KEY_TENANT}, {@code ACTOR_API_KEY_CONSUMING_PARTY}).
 *       This is what lets {@code /consume} require a {@code CONSUMING_PARTY} key specifically and
 *       {@code /revoke} require a console session specifically (spec FS-0.6b §3) — a scope alone
 *       cannot express "and only this kind of caller."
 * </ul>
 */
final class KhatmAuthorities {

  private KhatmAuthorities() {}

  static final String ACTOR_USER = "ACTOR_USER";
  static final String ACTOR_API_KEY_TENANT = "ACTOR_API_KEY_TENANT";
  static final String ACTOR_API_KEY_CONSUMING_PARTY = "ACTOR_API_KEY_CONSUMING_PARTY";

  static Collection<GrantedAuthority> build(CurrentActor.ActorKind kind, Set<String> scopes) {
    Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.size() + 1);
    for (String scope : scopes) {
      authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
    }
    authorities.add(new SimpleGrantedAuthority(actorAuthority(kind)));
    return authorities;
  }

  private static String actorAuthority(CurrentActor.ActorKind kind) {
    return switch (kind) {
      case USER -> ACTOR_USER;
      case API_KEY_TENANT -> ACTOR_API_KEY_TENANT;
      case API_KEY_CONSUMING_PARTY -> ACTOR_API_KEY_CONSUMING_PARTY;
    };
  }
}
