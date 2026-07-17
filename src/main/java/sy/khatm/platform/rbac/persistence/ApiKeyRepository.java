package sy.khatm.platform.rbac.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sy.khatm.platform.rbac.domain.ApiKey;

/**
 * Repository for {@link ApiKey} entities.
 *
 * <p>Module-private — only the {@code rbac} module's domain services may use this.
 */
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

  Optional<ApiKey> findByKeyPrefix(String keyPrefix);
}
