package sy.khatm.platform.rbac.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.rbac.api.ApiKeyOwnerRef;
import sy.khatm.platform.rbac.api.ApiKeyOwnerRef.OwnerKind;
import sy.khatm.platform.rbac.persistence.ApiKeyRepository;

/**
 * Implements {@code rbac :: api}'s {@link sy.khatm.platform.rbac.api.ApiKeyOwnerLookup} (spec
 * FS-1.5.4 D2) over the plain {@link ApiKeyRepository#findAllById} Spring Data already provides —
 * no new query, no new column.
 */
@Component
class ApiKeyOwnerLookupImpl implements sy.khatm.platform.rbac.api.ApiKeyOwnerLookup {

  private final ApiKeyRepository repository;

  ApiKeyOwnerLookupImpl(ApiKeyRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, ApiKeyOwnerRef> resolveOwners(Collection<UUID> apiKeyIds) {
    if (apiKeyIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, ApiKeyOwnerRef> resolved = new LinkedHashMap<>();
    for (ApiKey key : repository.findAllById(apiKeyIds)) {
      OwnerKind kind = key.isConsumingParty() ? OwnerKind.CONSUMING_PARTY : OwnerKind.TENANT;
      resolved.put(key.getId(), new ApiKeyOwnerRef(kind, key.getOwnerId()));
    }
    return resolved;
  }
}
