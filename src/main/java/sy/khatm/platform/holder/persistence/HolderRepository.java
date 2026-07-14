package sy.khatm.platform.holder.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sy.khatm.platform.holder.domain.Holder;

/**
 * Repository for {@link Holder} entities.
 *
 * <p>Module-private — only {@code HolderDirectoryService} may use this.
 */
public interface HolderRepository extends JpaRepository<Holder, UUID> {

  Optional<Holder> findByTenantIdAndPseudoRef(UUID tenantId, String pseudoRef);
}
