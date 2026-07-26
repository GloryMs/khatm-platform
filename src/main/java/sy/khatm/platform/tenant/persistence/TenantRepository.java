package sy.khatm.platform.tenant.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sy.khatm.platform.tenant.domain.Tenant;

/**
 * Repository for {@link Tenant} entities.
 *
 * <p>Module-private — only code within the {@code tenant} module may use this.
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

  Optional<Tenant> findBySlug(String slug);

  List<Tenant> findAllByOrderByCreatedAtDesc();
}
