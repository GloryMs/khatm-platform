package sy.khatm.platform.issuerclient.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.LocalizedTextConverter;

/**
 * An external system permitted to issue credentials for one tenant over machine-to-machine calls
 * (spec FS-2.7a D1).
 *
 * <p>Holds the key's lookup prefix (stored <em>without</em> the {@code khi_} tag) and the argon2id
 * hash of its secret — never the key itself. Implements {@link Persistable} for the same reason
 * {@code consumer.domain.ConsumingParty} does: the id is application-generated, so without it
 * Spring Data would issue a {@code SELECT} + {@code merge} for a brand-new row instead of a true
 * {@code INSERT}.
 *
 * <p>Module-private; other modules use {@code issuerclient :: api}.
 */
@Entity
@Table(name = "issuer_client")
public class IssuerClient implements Persistable<UUID> {

  /** Authenticates normally. */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** Temporarily rejected; can be resumed. */
  public static final String STATUS_SUSPENDED = "SUSPENDED";

  /** Rotated out: still authenticates until {@code retire_after}, then the sweep revokes it. */
  public static final String STATUS_RETIRING = "RETIRING";

  /** Final. Never authenticates again. */
  public static final String STATUS_REVOKED = "REVOKED";

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Convert(converter = LocalizedTextConverter.class)
  @Column(name = "name_i18n", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private LocalizedText nameI18n;

  @Column(name = "key_prefix", nullable = false, updatable = false)
  private String keyPrefix;

  @Column(name = "api_key_hash", nullable = false, updatable = false)
  private byte[] apiKeyHash;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "text[]")
  private String[] scopes;

  @Column(nullable = false)
  private String status;

  @Column(name = "rotated_from")
  private UUID rotatedFrom;

  @Column(name = "retire_after")
  private Instant retireAfter;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Transient private boolean isNew = true;

  @Override
  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    this.isNew = false;
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

  public String getKeyPrefix() {
    return keyPrefix;
  }

  public void setKeyPrefix(String keyPrefix) {
    this.keyPrefix = keyPrefix;
  }

  public byte[] getApiKeyHash() {
    return apiKeyHash;
  }

  public void setApiKeyHash(byte[] apiKeyHash) {
    this.apiKeyHash = apiKeyHash;
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

  public UUID getRotatedFrom() {
    return rotatedFrom;
  }

  public void setRotatedFrom(UUID rotatedFrom) {
    this.rotatedFrom = rotatedFrom;
  }

  public Instant getRetireAfter() {
    return retireAfter;
  }

  public void setRetireAfter(Instant retireAfter) {
    this.retireAfter = retireAfter;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(Instant lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(UUID createdBy) {
    this.createdBy = createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
