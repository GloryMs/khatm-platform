package sy.khatm.platform.key.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A signing key's public-facing record: state, validity window, and the provider-specific pointer
 * to its private half.
 *
 * <p>Only the public JWK is stored here (spec FS-0.2 §3.2 / FS-0.5 §3) — {@code providerRef} is an
 * opaque pointer the owning {@link KeyProvider} implementation understands (for {@link
 * SoftKeyProvider}, the keystore alias, which is always equal to {@code kid}). Private key material
 * never appears on this entity, in this table, or anywhere else outside the keystore file.
 *
 * <p>This class is module-private; external code must depend on {@code key :: api} ({@link
 * sy.khatm.platform.key.api.KeySigner}, {@link sy.khatm.platform.key.api.KeyVerifier}) instead.
 */
@Entity
@Table(name = "issuer_key")
public class IssuerKey {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String kid;

  @Column(nullable = false)
  private String algo;

  @Column(name = "public_jwk", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String publicJwkJson;

  @Column(nullable = false)
  private String provider;

  @Column(name = "provider_ref")
  private String providerRef;

  @Column(nullable = false)
  private String state;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to")
  private Instant validTo;

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

  public String getKid() {
    return kid;
  }

  public void setKid(String kid) {
    this.kid = kid;
  }

  public String getAlgo() {
    return algo;
  }

  public void setAlgo(String algo) {
    this.algo = algo;
  }

  public String getPublicJwkJson() {
    return publicJwkJson;
  }

  public void setPublicJwkJson(String publicJwkJson) {
    this.publicJwkJson = publicJwkJson;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getProviderRef() {
    return providerRef;
  }

  public void setProviderRef(String providerRef) {
    this.providerRef = providerRef;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public Instant getValidFrom() {
    return validFrom;
  }

  public void setValidFrom(Instant validFrom) {
    this.validFrom = validFrom;
  }

  public Instant getValidTo() {
    return validTo;
  }

  public void setValidTo(Instant validTo) {
    this.validTo = validTo;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
