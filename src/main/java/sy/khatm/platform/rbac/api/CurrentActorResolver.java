package sy.khatm.platform.rbac.api;

import java.util.Optional;

/**
 * Resolves the {@link CurrentActor} for the calling thread, without requiring the caller to depend
 * on Spring Security types directly (spec FS-0.6b §3).
 */
public interface CurrentActorResolver {

  /**
   * Resolve the current actor from the active {@code SecurityContext}.
   *
   * @return the current actor, or {@link Optional#empty()} if the current thread has no
   *     authenticated principal (an anonymous request, or a non-HTTP thread such as a scheduled
   *     worker task)
   */
  Optional<CurrentActor> resolve();
}
