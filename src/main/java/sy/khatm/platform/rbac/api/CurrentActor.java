package sy.khatm.platform.rbac.api;

import java.util.Set;
import java.util.UUID;

/**
 * The currently authenticated actor — a console user's session, or an API key's owner (spec FS-0.6b
 * §3).
 *
 * <p>{@code credential.domain.CredentialService#consume} is the first real consumer (KH-1.4.3's
 * {@code allowed_schemas} enforcement, spec §9) — it reads {@link #ownerId()} to resolve which
 * {@code consuming_party} row a {@link ActorKind#API_KEY_CONSUMING_PARTY} caller is scoped to.
 * Other modules that need to know "who is making this call" depend on {@link CurrentActorResolver}
 * instead of importing Spring Security types directly.
 *
 * @param kind whether this actor is a console user or one of the two API-key owner types
 * @param id the actor's id — the {@code app_user} row for {@link ActorKind#USER}, the {@code
 *     api_key} row for either API-key kind
 * @param tenantId the actor's tenant
 * @param scopes the actor's effective scopes (a user's roles' union, or an API key's own {@code
 *     scopes} column)
 * @param ownerId the owning {@code consuming_party} row's id ({@link
 *     ActorKind#API_KEY_CONSUMING_PARTY} only, KH-1.4.3); {@code null} for every other actor kind
 */
public record CurrentActor(
    ActorKind kind, UUID id, UUID tenantId, Set<String> scopes, UUID ownerId) {

  /** Which kind of principal authenticated this request (spec FS-0.6b §3). */
  public enum ActorKind {
    /** A console session, authenticated by username/password (spec FS-0.6b D1). */
    USER,
    /** An API key owned by the tenant itself (spec FS-0.6b D3) — e.g. may call {@code /issue}. */
    API_KEY_TENANT,
    /**
     * An API key owned by one registered consuming party (spec FS-0.6b D3) — the only kind {@code
     * /consume} accepts (SEC §7).
     */
    API_KEY_CONSUMING_PARTY
  }
}
