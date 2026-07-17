package sy.khatm.platform.rbac.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.LocalizedTextConverter;

/**
 * A role: a named bundle of scopes (spec FS-0.2 §3.10, spec FS-0.6b D5).
 *
 * <p>Deliberately lean, per D5: {@code scopes} is a flat {@code text[]}, not a normalized
 * Permission table — {@code V1__baseline.sql} already seeded the three default roles
 * (PLATFORM_ADMIN, TENANT_ADMIN, ISSUER_OPERATOR) this way, and the full RBAC model (per-endpoint
 * Permission rows, admin-managed role editing) is KH-2.2.
 *
 * <p>This class is module-private; external code must depend on {@code rbac :: api} instead.
 */
@Entity
@Table(name = "role")
public class Role {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String code;

  @Convert(converter = LocalizedTextConverter.class)
  @Column(name = "name_i18n", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private LocalizedText nameI18n;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "text[]")
  private String[] scopes;

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

  public LocalizedText getNameI18n() {
    return nameI18n;
  }

  public void setNameI18n(LocalizedText nameI18n) {
    this.nameI18n = nameI18n;
  }

  public String[] getScopes() {
    return scopes;
  }

  public void setScopes(String[] scopes) {
    this.scopes = scopes;
  }
}
