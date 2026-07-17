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
 * <p>API-key authentication for a consuming party is now the {@code rbac} module's {@code api_key}
 * table (owner_type {@code CONSUMING_PARTY}, spec FS-0.6b D3) — this entity no longer carries a key
 * hash of its own (KH-0.2.1's {@code api_key_hash} stand-in column was dropped by {@code
 * V2__auth_api_keys.sql}). Real onboarding (issuing that key, scoping this party to specific
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
