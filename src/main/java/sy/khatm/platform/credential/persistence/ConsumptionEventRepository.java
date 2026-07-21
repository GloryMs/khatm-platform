package sy.khatm.platform.credential.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sy.khatm.platform.credential.domain.ConsumptionEvent;

/**
 * Repository for {@link ConsumptionEvent} records.
 *
 * <p>Module-private — only the domain service may write consumption events.
 */
public interface ConsumptionEventRepository extends JpaRepository<ConsumptionEvent, UUID> {

  /**
   * KH-1.4.1/1.4.2 — the double-submit-race fallback path: after a unique-violation on {@code
   * idempotency_key}, look up the row the winning concurrent caller actually recorded.
   */
  Optional<ConsumptionEvent> findByIdempotencyKey(String idempotencyKey);
}
