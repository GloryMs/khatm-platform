package sy.khatm.platform.rbac.domain;

import java.util.Set;
import java.util.UUID;
import sy.khatm.platform.rbac.api.CurrentActor;

/**
 * A successfully verified API key's identity (spec FS-0.6b D2/D3), returned by {@link
 * ApiKeyService#verify}.
 *
 * @param apiKeyId the {@code api_key} row's own id — used as the audit {@code actor_id}
 * @param tenantId the key's tenant
 * @param kind {@link CurrentActor.ActorKind#API_KEY_TENANT} or {@link
 *     CurrentActor.ActorKind#API_KEY_CONSUMING_PARTY} — never {@code USER}
 * @param ownerId the owning consuming party's id ({@code CONSUMING_PARTY} keys only); {@code null}
 *     for a {@code TENANT} key
 * @param scopes the key's own granted scopes
 */
public record VerifiedApiKey(
    UUID apiKeyId, UUID tenantId, CurrentActor.ActorKind kind, UUID ownerId, Set<String> scopes) {}
