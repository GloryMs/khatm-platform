package sy.khatm.platform.tenant.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;
import sy.khatm.platform.tenant.persistence.TenantRepository;

/**
 * Default {@link TenantDirectory} implementation — the runtime cross-module lookup SPI (spec FS-2.1
 * D1/D7/D8).
 *
 * <p>This class is module-private. External code must depend on {@link TenantDirectory}, not this
 * class.
 */
@Service
class TenantDirectoryService implements TenantDirectory {

  private final TenantRepository tenants;

  TenantDirectoryService(TenantRepository tenants) {
    this.tenants = tenants;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TenantRef> findById(UUID tenantId) {
    return tenants.findById(tenantId).map(TenantDirectoryService::toRef);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TenantRef> findBySlug(String slug) {
    return tenants.findBySlug(slug).map(TenantDirectoryService::toRef);
  }

  @Override
  @Transactional(readOnly = true)
  public List<TenantRef> ancestors(UUID tenantId) {
    List<TenantRef> chain = new ArrayList<>();
    Set<UUID> visited = new HashSet<>();
    Optional<Tenant> current = tenants.findById(tenantId);
    if (current.isEmpty()) {
      return chain;
    }
    UUID parentId = current.get().getParentTenantId();
    // Bounded by construction (max depth three, §7) — visited guards defensively against a
    // pre-existing data cycle that somehow bypassed both the service-layer check and the
    // V16__tenant_hierarchy.sql trigger, rather than looping forever if one is ever found.
    while (parentId != null && visited.add(parentId)) {
      Optional<Tenant> parent = tenants.findById(parentId);
      if (parent.isEmpty()) {
        break;
      }
      chain.add(toRef(parent.get()));
      parentId = parent.get().getParentTenantId();
    }
    return chain;
  }

  @Override
  @Transactional(readOnly = true)
  public List<TenantRef> directChildren(UUID tenantId) {
    return tenants.findAllByParentTenantId(tenantId).stream()
        .map(TenantDirectoryService::toRef)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<TenantRef> descendants(UUID tenantId) {
    List<TenantRef> subtree = new ArrayList<>();
    collectDescendants(tenantId, subtree);
    return subtree;
  }

  /** Bounded by construction (max depth three, §7) — one call per level below {@code tenantId}. */
  private void collectDescendants(UUID tenantId, List<TenantRef> accumulator) {
    for (Tenant child : tenants.findAllByParentTenantId(tenantId)) {
      accumulator.add(toRef(child));
      collectDescendants(child.getId(), accumulator);
    }
  }

  private static TenantRef toRef(Tenant tenant) {
    return new TenantRef(
        tenant.getId(), tenant.getSlug(), tenant.getStatus(), tenant.getNameI18n());
  }
}
