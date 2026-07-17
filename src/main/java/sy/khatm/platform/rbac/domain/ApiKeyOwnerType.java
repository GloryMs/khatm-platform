package sy.khatm.platform.rbac.domain;

/** Who an API key acts on behalf of (spec FS-0.6b D3, {@code api_key.owner_type}). */
public enum ApiKeyOwnerType {
  /**
   * The tenant itself — may be granted {@code issue}/{@code verify}/{@code revoke}/{@code admin}.
   */
  TENANT,
  /** One registered consuming party — the only owner type {@code /consume} accepts (SEC §7). */
  CONSUMING_PARTY
}
