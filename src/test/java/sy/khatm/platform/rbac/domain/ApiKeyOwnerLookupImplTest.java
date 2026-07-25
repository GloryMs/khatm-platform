package sy.khatm.platform.rbac.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.rbac.api.ApiKeyOwnerLookup;
import sy.khatm.platform.rbac.api.ApiKeyOwnerRef;
import sy.khatm.platform.rbac.api.ApiKeyOwnerRef.OwnerKind;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-1.5.4 D2, KH-1.1.5-BE — {@link ApiKeyOwnerLookupImpl}, the batch {@code api_key.id ->
 * owner} resolution {@code credential.web}'s activity/consuming-party-stats endpoints depend on to
 * attribute a historical {@code audit_log.actor_id} to its owning consuming party.
 */
class ApiKeyOwnerLookupImplTest extends IntegrationTestSupport {

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private ApiKeyOwnerLookup ownerLookup;

  @Test
  void resolveOwners_tenantKey_resolvesToTenantKindWithNullOwnerId() {
    CreatedApiKey key = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));

    Map<UUID, ApiKeyOwnerRef> resolved = ownerLookup.resolveOwners(List.of(key.id()));

    ApiKeyOwnerRef ref = resolved.get(key.id());
    assertThat(ref).isNotNull();
    assertThat(ref.kind()).isEqualTo(OwnerKind.TENANT);
    assertThat(ref.ownerId()).isNull();
  }

  @Test
  void resolveOwners_consumingPartyKey_resolvesToConsumingPartyKindWithOwnerId() {
    UUID partyId = UUID.randomUUID();
    CreatedApiKey key =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, partyId, Set.of("consume"));

    Map<UUID, ApiKeyOwnerRef> resolved = ownerLookup.resolveOwners(List.of(key.id()));

    ApiKeyOwnerRef ref = resolved.get(key.id());
    assertThat(ref).isNotNull();
    assertThat(ref.kind()).isEqualTo(OwnerKind.CONSUMING_PARTY);
    assertThat(ref.ownerId()).isEqualTo(partyId);
  }

  @Test
  void resolveOwners_batchesMultipleIdsInOneCall() {
    CreatedApiKey tenantKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));
    UUID partyId = UUID.randomUUID();
    CreatedApiKey partyKey =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, partyId, Set.of("consume"));

    Map<UUID, ApiKeyOwnerRef> resolved =
        ownerLookup.resolveOwners(List.of(tenantKey.id(), partyKey.id()));

    assertThat(resolved).hasSize(2);
    assertThat(resolved.get(tenantKey.id()).kind()).isEqualTo(OwnerKind.TENANT);
    assertThat(resolved.get(partyKey.id()).kind()).isEqualTo(OwnerKind.CONSUMING_PARTY);
  }

  @Test
  void resolveOwners_unknownId_isSimplyAbsent() {
    Map<UUID, ApiKeyOwnerRef> resolved = ownerLookup.resolveOwners(List.of(UUID.randomUUID()));

    assertThat(resolved).isEmpty();
  }

  @Test
  void resolveOwners_emptyInput_returnsEmptyMap() {
    assertThat(ownerLookup.resolveOwners(List.of())).isEmpty();
  }
}
