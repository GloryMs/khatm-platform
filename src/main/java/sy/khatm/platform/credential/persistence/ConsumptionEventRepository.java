package sy.khatm.platform.credential.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.domain.ConsumptionEvent;

/**
 * Repository for {@link ConsumptionEvent} records.
 *
 * <p>Module-private — only the domain service may write consumption events.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale.
 */
@Transactional(readOnly = true)
public interface ConsumptionEventRepository extends JpaRepository<ConsumptionEvent, UUID> {

  /**
   * KH-1.4.1/1.4.2 — the double-submit-race fallback path: after a unique-violation on {@code
   * idempotency_key}, look up the row the winning concurrent caller actually recorded.
   */
  Optional<ConsumptionEvent> findByIdempotencyKey(String idempotencyKey);

  /**
   * The most recent consumption of a credential, if any (spec FS-1.6 D3's {@code holder-status}'s
   * {@code lastConsumedAt} field).
   */
  Optional<ConsumptionEvent> findTopByCredentialIdOrderByConsumedAtDesc(UUID credentialId);
}
