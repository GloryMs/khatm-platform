package sy.khatm.platform.consumer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.LocalizedTextConverter;

/**
 * A verifier/relying party permitted to consume credentials.
 *
 * <p>{@code apiKeyHash} never stores the raw key — only its hash, matching every other credential
 * field in the platform (SEC §9). Real onboarding (issuing an API key, scoping it to specific
 * schemas via {@code consuming_party_schema}) is KH-1.4.3.
 *
 * <p>This class is module-private; external code must depend on {@code consumer :: api} instead.
 */
@Entity
@Table(name = "consuming_party")
public class ConsumingParty {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Convert(converter = LocalizedTextConverter.class)
  @Column(name = "name_i18n", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private LocalizedText nameI18n;

  @Column(name = "api_key_hash", nullable = false, unique = true)
  private byte[] apiKeyHash;

  @Column(nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public void setTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }

  public LocalizedText getNameI18n() {
    return nameI18n;
  }

  public void setNameI18n(LocalizedText nameI18n) {
    this.nameI18n = nameI18n;
  }

  public byte[] getApiKeyHash() {
    return apiKeyHash;
  }

  public void setApiKeyHash(byte[] apiKeyHash) {
    this.apiKeyHash = apiKeyHash;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
