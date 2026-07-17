package sy.khatm.platform.rbac.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A programmatic API key (spec FS-0.6b §4, D2–D4).
 *
 * <p>{@code keyPrefix} is the lookup column — 8 base62 characters, safe to log and to show in the
 * console ({@code khk_&lt;env&gt;_&lt;prefix&gt;} is the key's visible identifier). {@code keyHash}
 * is SHA-256 of the 32-character base62 secret half (D4 — the secret's own entropy makes a fast
 * hash safe here, unlike a human password); the raw secret is shown to the caller exactly once, at
 * creation, and never stored (spec FS-0.6b §4).
 *
 * <p>{@code ownerType}/{@code ownerId}: {@code TENANT} keys act on behalf of the tenant itself
 * ({@code ownerId} {@code null}); {@code CONSUMING_PARTY} keys act on behalf of one registered
 * consuming party ({@code ownerId} its {@code consuming_party.id}) and are the only key type {@code
 * /consume} accepts (SEC §7).
 *
 * <p>This class is module-private; external code must depend on {@code rbac :: api} instead.
 */
@Entity
@Table(name = "api_key")
public class ApiKey {

  static final String OWNER_TENANT = "TENANT";
  static final String OWNER_CONSUMING_PARTY = "CONSUMING_PARTY";
  static final String STATUS_ACTIVE = "ACTIVE";
  static final String STATUS_REVOKED = "REVOKED";

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "owner_type", nullable = false)
  private String ownerType;

  @Column(name = "owner_id")
  private UUID ownerId;

  @Column(name = "key_prefix", nullable = false, unique = true)
  private String keyPrefix;

  @Column(name = "key_hash", nullable = false)
  private byte[] keyHash;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "text[]")
  private String[] scopes;

  @Column(nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

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

  public String getOwnerType() {
    return ownerType;
  }

  public void setOwnerType(String ownerType) {
    this.ownerType = ownerType;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(UUID ownerId) {
    this.ownerId = ownerId;
  }

  public String getKeyPrefix() {
    return keyPrefix;
  }

  public void setKeyPrefix(String keyPrefix) {
    this.keyPrefix = keyPrefix;
  }

  public byte[] getKeyHash() {
    return keyHash;
  }

  public void setKeyHash(byte[] keyHash) {
    this.keyHash = keyHash;
  }

  public String[] getScopes() {
    return scopes;
  }

  public void setScopes(String[] scopes) {
    this.scopes = scopes;
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

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(Instant revokedAt) {
    this.revokedAt = revokedAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(Instant lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }

  boolean isActive() {
    return STATUS_ACTIVE.equals(status);
  }

  boolean isConsumingParty() {
    return OWNER_CONSUMING_PARTY.equals(ownerType);
  }
}
