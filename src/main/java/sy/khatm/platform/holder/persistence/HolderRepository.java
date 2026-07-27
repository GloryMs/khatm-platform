package sy.khatm.platform.holder.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.holder.domain.Holder;

/**
 * Repository for {@link Holder} entities.
 *
 * <p>Module-private — only {@code HolderDirectoryService} may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale.
 */
@Transactional(readOnly = true)
public interface HolderRepository extends JpaRepository<Holder, UUID> {

  Optional<Holder> findByTenantIdAndPseudoRef(UUID tenantId, String pseudoRef);
}
