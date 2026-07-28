package sy.khatm.platform.rbac.domain;

/** Who an API key acts on behalf of (spec FS-0.6b D3, {@code api_key.owner_type}). */
public enum ApiKeyOwnerType {
  /**
   * The tenant itself — may be granted any scope in {@code rbac.security.ScopeRegistry} except
   * {@code platform:admin}, which no API key may ever carry (spec FS-2.2 D4 reserves it for a
   * console session under the default tenant, checked live via {@code
   * shared.OnBehalfOfExecutor}/{@code SecurityContextHolder}, never a long-lived key).
   */
  TENANT,
  /** One registered consuming party — the only owner type {@code /consume} accepts (SEC §7). */
  CONSUMING_PARTY
}
