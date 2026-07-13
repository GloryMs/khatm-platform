package sy.khatm.platform.holder.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A pseudonymous holder — never a real name or national ID (P1 rule).
 *
 * <p>{@code walletJwk} (the wallet's key-binding public key, {@code cnf}) stays {@code null} until
 * Phase 3 wallet binding lands; it is mapped here only so the column round-trips correctly.
 *
 * <p>This class is module-private; external code must depend on {@code holder :: api} instead.
 */
@Entity
@Table(name = "holder")
public class Holder {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "pseudo_ref", nullable = false)
  private String pseudoRef;

  @Column(name = "wallet_jwk", columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String walletJwkJson;

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

  public String getPseudoRef() {
    return pseudoRef;
  }

  public void setPseudoRef(String pseudoRef) {
    this.pseudoRef = pseudoRef;
  }

  public String getWalletJwkJson() {
    return walletJwkJson;
  }

  public void setWalletJwkJson(String walletJwkJson) {
    this.walletJwkJson = walletJwkJson;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
