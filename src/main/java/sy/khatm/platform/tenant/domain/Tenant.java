package sy.khatm.platform.tenant.domain;

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
 * The issuing organisation a row belongs to.
 *
 * <p>V1 seeds exactly one row ({@link sy.khatm.platform.shared.TenantContext#DEFAULT_TENANT_ID})
 * that every other table's {@code tenant_id} column points at. Full tenant lifecycle management
 * (create/suspend, per-tenant quotas) is deferred to KH-2.x — this entity exists in KH-0.2.1 only
 * so that Hibernate's {@code ddl-auto: validate} can confirm the {@code tenant} table matches the
 * baseline schema.
 *
 * <p>This class is module-private; external code must not depend on it directly.
 */
@Entity
@Table(name = "tenant")
public class Tenant {

  @Id private UUID id;

  @Column(nullable = false, unique = true)
  private String slug;

  @Convert(converter = LocalizedTextConverter.class)
  @Column(name = "name_i18n", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private LocalizedText nameI18n;

  @Column(nullable = false)
  private String type;

  private String did;

  @Column(name = "deploy_mode", nullable = false)
  private String deployMode;

  /**
   * The tenant's current signing-key provider (spec FS-2.3 D5/D6, veto V3) — {@code SOFT} for every
   * newly onboarded tenant; changes only as a side effect of {@code key.domain
   * .KeyLifecycleService#rotate} onto a different provider, via {@link
   * sy.khatm.platform.key.events.KeyRotated} (this module consumes it, {@code key} never writes
   * here directly — see {@code key/package-info.java}'s dependency-direction note). Not the source
   * of truth for any individual key's provider (that's {@code issuer_key.provider}, per row); this
   * column is the tenant-level "which provider is current" view, e.g. for a future console badge.
   */
  @Column(name = "key_provider", nullable = false)
  private String keyProvider;

  @Column(nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public LocalizedText getNameI18n() {
    return nameI18n;
  }

  public void setNameI18n(LocalizedText nameI18n) {
    this.nameI18n = nameI18n;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getDid() {
    return did;
  }

  public void setDid(String did) {
    this.did = did;
  }

  public String getDeployMode() {
    return deployMode;
  }

  public void setDeployMode(String deployMode) {
    this.deployMode = deployMode;
  }

  public String getKeyProvider() {
    return keyProvider;
  }

  public void setKeyProvider(String keyProvider) {
    this.keyProvider = keyProvider;
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

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
