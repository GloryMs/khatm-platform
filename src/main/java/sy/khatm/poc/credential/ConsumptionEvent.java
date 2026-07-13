package sy.khatm.poc.credential;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consumption_event")
public class ConsumptionEvent {

    @Id
    private UUID id;

    @Column(name = "credential_id", nullable = false)
    private UUID credentialId;

    @Column(nullable = false)
    private String consumer;

    @Column(name = "consumed_at", nullable = false)
    private Instant consumedAt;

    @Column(nullable = false)
    private String mode;   // online / offline

    public ConsumptionEvent() {}

    public ConsumptionEvent(UUID credentialId, String consumer, String mode) {
        this.id = UUID.randomUUID();
        this.credentialId = credentialId;
        this.consumer = consumer;
        this.mode = mode;
        this.consumedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCredentialId() { return credentialId; }
    public String getConsumer() { return consumer; }
    public Instant getConsumedAt() { return consumedAt; }
    public String getMode() { return mode; }
}
