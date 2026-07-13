package sy.khatm.platform.credential.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of a single credential consumption.
 *
 * <p>Written once when a consumption succeeds; never updated or deleted. Effectively append-only at
 * the application level (full append-only enforcement via DB trigger arrives in KH-0.6 / KH-1.5).
 *
 * <p>This class is module-private.
 */
@Entity
@Table(name = "consumption_event")
public class ConsumptionEvent {

  @Id private UUID id;

  @Column(name = "credential_id", nullable = false)
  private UUID credentialId;

  /** Consumer organisation code or device identifier. Never a personal name (P1 rule). */
  @Column(nullable = false)
  private String consumer;

  @Column(name = "consumed_at", nullable = false)
  private Instant consumedAt;

  /** {@code online} or {@code offline} — records which verification path was used. */
  @Column(nullable = false)
  private String mode;

  public ConsumptionEvent() {}

  public ConsumptionEvent(UUID credentialId, String consumer, String mode) {
    this.id = UUID.randomUUID();
    this.credentialId = credentialId;
    this.consumer = consumer;
    this.mode = mode;
    this.consumedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getCredentialId() {
    return credentialId;
  }

  public String getConsumer() {
    return consumer;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }

  public String getMode() {
    return mode;
  }
}
