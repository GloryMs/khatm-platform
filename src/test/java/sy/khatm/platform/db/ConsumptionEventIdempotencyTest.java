package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import sy.khatm.platform.consumer.api.ConsumingPartyRef;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.ConsumptionEvent;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.credential.persistence.ConsumptionEventRepository;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.2 §5 acceptance criterion 4 — inserting a {@code consumption_event} with a duplicate {@code
 * idempotency_key} fails on the table's {@code UNIQUE} constraint.
 *
 * <p>This is the durable fallback for the double-submit guard (spec FS-0.2 §3.9): even if the Redis
 * fast-path cache is unavailable, two consumption attempts sharing an idempotency key can never
 * both be recorded.
 */
class ConsumptionEventIdempotencyTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private ConsumingPartyRegistry consumingParties;
  @Autowired private ConsumptionEventRepository consumptionEvents;

  @Test
  void save_duplicateIdempotencyKey_violatesUniqueConstraint() throws Exception {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "IdempotencyProbe/v1", "holder-idempotency-probe", 5, 60, Map.of(), null, null));
    ConsumingPartyRef party = consumingParties.ensure("idempotency-probe-party");
    UUID credentialId = UUID.fromString(issued.id());
    String sharedKey = "idempotency-probe-" + UUID.randomUUID();

    consumptionEvents.saveAndFlush(
        new ConsumptionEvent(
            TenantContext.current(), credentialId, party.id(), sharedKey, "ONLINE"));

    assertThatThrownBy(
            () ->
                consumptionEvents.saveAndFlush(
                    new ConsumptionEvent(
                        TenantContext.current(), credentialId, party.id(), sharedKey, "ONLINE")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
