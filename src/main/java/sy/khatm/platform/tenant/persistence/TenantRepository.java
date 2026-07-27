package sy.khatm.platform.tenant.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.tenant.domain.Tenant;

/**
 * Repository for {@link Tenant} entities.
 *
 * <p>Module-private — only code within the {@code tenant} module may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale. ({@code tenant}
 * itself is excluded from RLS, but {@code TenantAdminService#create} is deliberately
 * non-{@code @Transactional} too, so this repository's bare calls need the same safety net.)
 */
@Transactional(readOnly = true)
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

  Optional<Tenant> findBySlug(String slug);

  List<Tenant> findAllByOrderByCreatedAtDesc();
}
