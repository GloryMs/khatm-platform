package sy.khatm.platform.credential.domain;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.persistence.ConsumptionEventRepository;
import sy.khatm.platform.credential.persistence.CredentialRepository;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.status.api.StatusListRevoker;

/**
 * The atomic unit of a single consume attempt (KH-1.4.1/1.4.2 closure): the eligibility decrement,
 * the {@code consumption_event} insert, and its audit row commit or roll back together, in their
 * own fresh transaction — never {@link CredentialService#consume}'s.
 *
 * <p><b>Why a separate bean, not a private method on {@code CredentialService}:</b> after a unique
 * violation here, {@code consume} needs to observe a clean (non-aborted) connection to look up the
 * winning event and answer the loser's caller without a generic 500. Postgres aborts an entire
 * physical transaction on any statement error until it is rolled back, so the decrement + event
 * insert must run in a transaction {@code consume} itself is never inside — and Spring can only
 * apply {@code @Transactional} through a real bean proxy, not a self-invoked method on the same
 * instance.
 *
 * <p><b>D1 exactly-once {@code EXHAUSTED} transition (spec FS-1.6):</b> {@link
 * CredentialRepository#consumeOne}'s {@code WHERE uses_remaining > 0} guard means at most one
 * successful call ever observes the row transition from 1 to 0 — every later call against an
 * already-exhausted row fails the WHERE clause and returns before reaching this method's body at
 * all. So re-reading {@code usesRemaining} right after our own successful decrement, inside this
 * same transaction, is a safe, lock-free way to detect "I am the caller who just exhausted this
 * credential" exactly once, with no separate guard column. On that detection this method reuses
 * {@link StatusListRevoker#revoke} — the exact same bit-flip/republish path {@code
 * CredentialService#revoke} uses — so an exhausted credential's status-list bit flips via identical
 * sweep/publish/per-tenant-signing mechanics, never a second implementation.
 *
 * <p>This class is module-private.
 */
@Service
class AtomicConsumptionRecorder {

  private final CredentialRepository credentials;
  private final ConsumptionEventRepository events;
  private final AuditService audit;
  private final StatusListRevoker statusRevoker;

  AtomicConsumptionRecorder(
      CredentialRepository credentials,
      ConsumptionEventRepository events,
      AuditService audit,
      StatusListRevoker statusRevoker) {
    this.credentials = credentials;
    this.events = events;
    this.audit = audit;
    this.statusRevoker = statusRevoker;
  }

  /**
   * Attempt the atomic decrement; if it succeeds, record the consumption event and its audit row in
   * the same transaction.
   *
   * @return {@code true} if this call actually consumed a use, {@code false} if the credential was
   *     not eligible (no uses left / revoked / outside its validity window)
   * @throws org.springframework.dao.DataIntegrityViolationException if {@code idempotencyKey} was
   *     already recorded by a concurrent, successful call (KH-1.4.1/1.4.2's double-submit race —
   *     two callers sharing one idempotency key can both legitimately pass the eligibility check
   *     when more than one use remains). {@link org.springframework.transaction.annotation
   *     .Transactional}'s proxy rolls back this whole method's transaction — including the
   *     decrement just performed — before the exception reaches the caller, so a losing caller
   *     never actually consumes a use.
   */
  @Transactional
  boolean tryConsume(UUID credentialId, UUID consumingPartyId, String idempotencyKey) {
    int updated = credentials.consumeOne(credentialId);
    if (updated != 1) {
      return false;
    }
    events.saveAndFlush(
        new ConsumptionEvent(
            TenantContext.current(), credentialId, consumingPartyId, idempotencyKey, "ONLINE"));
    audit.record(AuditAction.CREDENTIAL_CONSUMED, "credential", credentialId.toString(), null);

    // D1: this decrement just succeeded, so this row is the sole source of truth on whether it
    // was the one that brought usesRemaining to 0 — see class Javadoc for why re-reading it here
    // is exactly-once-safe with no separate guard state.
    Credential credential =
        credentials
            .findById(credentialId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "credential "
                            + credentialId
                            + " must still exist right after its own"
                            + " successful consumeOne update"));
    if (credential.getUsesRemaining() == 0) {
      statusRevoker.revoke(credential.getStatusListId(), credential.getStatusIdx());
      audit.record(AuditAction.CREDENTIAL_EXHAUSTED, "credential", credentialId.toString(), null);
    }
    return true;
  }
}
