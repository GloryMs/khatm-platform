package sy.khatm.platform.schema.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.LocalizedTextConverter;

/**
 * A versioned credential type definition (e.g. {@code CriminalRecordExtract} v1).
 *
 * <p>{@code claimsDefJson} holds the raw claim-field definition (types, required flags, bilingual
 * labels — spec FS-0.2 §3.3) as JSON text; a typed Java model for it arrives with the schema editor
 * (KH-1.x). The {@code default_validity} column (a Postgres {@code interval}) is intentionally left
 * unmapped here — nothing reads it yet and Hibernate {@code validate} mode does not require every
 * column to be mapped.
 *
 * <p>This class is module-private; external code must depend on {@code schema :: api} instead.
 */
@Entity
@Table(name = "credential_schema")
public class CredentialSchema {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private int version;

  @Convert(converter = LocalizedTextConverter.class)
  @Column(name = "name_i18n", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private LocalizedText nameI18n;

  /** Raw JSON text — claim field definitions (type, required, label_i18n per field). */
  @Column(name = "claims_def", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String claimsDefJson;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "sd_fields", nullable = false, columnDefinition = "text[]")
  private String[] sdFields;

  @Column(name = "default_max_uses", nullable = false)
  private int defaultMaxUses;

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

  public UUID getTenantId() {
    return tenantId;
  }

  public void setTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public LocalizedText getNameI18n() {
    return nameI18n;
  }

  public void setNameI18n(LocalizedText nameI18n) {
    this.nameI18n = nameI18n;
  }

  public String getClaimsDefJson() {
    return claimsDefJson;
  }

  public void setClaimsDefJson(String claimsDefJson) {
    this.claimsDefJson = claimsDefJson;
  }

  public String[] getSdFields() {
    return sdFields;
  }

  public void setSdFields(String[] sdFields) {
    this.sdFields = sdFields;
  }

  public int getDefaultMaxUses() {
    return defaultMaxUses;
  }

  public void setDefaultMaxUses(int defaultMaxUses) {
    this.defaultMaxUses = defaultMaxUses;
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
