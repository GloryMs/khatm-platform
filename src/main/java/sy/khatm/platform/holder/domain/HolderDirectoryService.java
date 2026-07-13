package sy.khatm.platform.holder.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.holder.api.HolderDirectory;
import sy.khatm.platform.holder.api.HolderRef;
import sy.khatm.platform.holder.persistence.HolderRepository;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;

/**
 * Default {@link HolderDirectory} implementation.
 *
 * <p>This class is module-private. External code must depend on {@link HolderDirectory}, not this
 * class.
 */
@Service
class HolderDirectoryService implements HolderDirectory {

  private final HolderRepository holders;

  HolderDirectoryService(HolderRepository holders) {
    this.holders = holders;
  }

  @Override
  @Transactional
  public HolderRef ensureHolder(String pseudoRef) {
    UUID tenantId = TenantContext.current();
    Optional<Holder> existing = holders.findByTenantIdAndPseudoRef(tenantId, pseudoRef);
    if (existing.isPresent()) {
      return toRef(existing.get());
    }

    Holder holder = new Holder();
    holder.setId(Uuidv7.generate());
    holder.setTenantId(tenantId);
    holder.setPseudoRef(pseudoRef);
    holder.setCreatedAt(Instant.now());
    holders.save(holder);
    return toRef(holder);
  }

  private static HolderRef toRef(Holder holder) {
    return new HolderRef(holder.getId(), holder.getPseudoRef());
  }
}
