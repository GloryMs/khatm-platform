package sy.khatm.platform.tenant.domain;

import java.util.Optional;
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

  private static TenantRef toRef(Tenant tenant) {
    return new TenantRef(tenant.getId(), tenant.getSlug(), tenant.getStatus());
  }
}
