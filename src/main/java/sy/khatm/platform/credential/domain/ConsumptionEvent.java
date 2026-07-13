package sy.khatm.platform.credential.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import sy.khatm.platform.shared.Uuidv7;

/**
 * Immutable record of a single credential consumption.
 *
 * <p>Written once when a consumption succeeds; never updated or deleted. {@code idempotencyKey} is
 * the durable fallback for the double-submit guard — the Redis cache in {@code CredentialService}
 * is only a fast path (spec FS-0.2 §3.9). {@code merkleLeaf} / {@code receiptSig} stay {@code null}
 * until offline consumption receipts arrive (Phase 3).
 *
 * <p>This class is module-private.
 */
@Entity
@Table(name = "consumption_event")
public class ConsumptionEvent {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "credential_id", nullable = false)
  private UUID credentialId;

  @Column(name = "consuming_party_id", nullable = false)
  private UUID consumingPartyId;

  @Column(name = "idempotency_key", nullable = false, unique = true)
  private String idempotencyKey;

  /** {@code ONLINE} or {@code OFFLINE} — records which verification path was used. */
  @Column(nullable = false)
  private String mode;

  @Column(name = "consumed_at", nullable = false)
  private Instant consumedAt;

  public ConsumptionEvent() {}

  public ConsumptionEvent(
      UUID tenantId, UUID credentialId, UUID consumingPartyId, String idempotencyKey, String mode) {
    this.id = Uuidv7.generate();
    this.tenantId = tenantId;
    this.credentialId = credentialId;
    this.consumingPartyId = consumingPartyId;
    this.idempotencyKey = idempotencyKey;
    this.mode = mode;
    this.consumedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getCredentialId() {
    return credentialId;
  }

  public UUID getConsumingPartyId() {
    return consumingPartyId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getMode() {
    return mode;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }
}
