package sy.khatm.platform.credential.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sy.khatm.platform.credential.domain.ConsumptionEvent;

/**
 * Repository for {@link ConsumptionEvent} records.
 *
 * <p>Module-private — only the domain service may write consumption events.
 */
public interface ConsumptionEventRepository extends JpaRepository<ConsumptionEvent, UUID> {}
