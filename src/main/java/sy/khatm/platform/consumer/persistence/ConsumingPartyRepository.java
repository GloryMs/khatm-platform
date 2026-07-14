package sy.khatm.platform.consumer.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sy.khatm.platform.consumer.domain.ConsumingParty;

/**
 * Repository for {@link ConsumingParty} entities.
 *
 * <p>Module-private — only {@code ConsumingPartyRegistryService} may use this.
 */
public interface ConsumingPartyRepository extends JpaRepository<ConsumingParty, UUID> {

  Optional<ConsumingParty> findByTenantIdAndApiKeyHash(UUID tenantId, byte[] apiKeyHash);
}
