package sy.khatm.platform.rbac.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.rbac.domain.AppUser;

/**
 * Repository for {@link AppUser} entities.
 *
 * <p>Module-private — only the {@code rbac} module's domain services may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale.
 */
@Transactional(readOnly = true)
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

  Optional<AppUser> findByTenantIdAndUsername(UUID tenantId, String username);

  boolean existsByTenantId(UUID tenantId);
}
