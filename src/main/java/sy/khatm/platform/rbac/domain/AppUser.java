package sy.khatm.platform.rbac.domain;

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
 * A console user (spec FS-0.2 §3.10, spec FS-0.6b).
 *
 * <p>{@code passwordHash} is always an argon2id hash (spec FS-0.6b D4, {@code
 * Argon2PasswordEncoder}) — the raw password is never persisted or logged anywhere (SEC §9.7).
 * {@code status} distinguishes an administrative lock ({@code LOCKED}/{@code DISABLED}, set by an
 * operator) from the Redis-TTL-based temporary lockout {@link AuthService} enforces after repeated
 * failed logins (spec FS-0.6b D6) — the two are deliberately independent (D6).
 *
 * <p>This class is module-private; external code must depend on {@code rbac :: api} instead.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

  static final String STATUS_ACTIVE = "ACTIVE";
  static final String STATUS_LOCKED = "LOCKED";
  static final String STATUS_DISABLED = "DISABLED";

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String username;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Convert(converter = LocalizedTextConverter.class)
  @Column(name = "display_name_i18n", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private LocalizedText displayNameI18n;

  @Column(name = "preferred_lang", nullable = false)
  private String preferredLang;

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

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public LocalizedText getDisplayNameI18n() {
    return displayNameI18n;
  }

  public void setDisplayNameI18n(LocalizedText displayNameI18n) {
    this.displayNameI18n = displayNameI18n;
  }

  public String getPreferredLang() {
    return preferredLang;
  }

  public void setPreferredLang(String preferredLang) {
    this.preferredLang = preferredLang;
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

  boolean isActive() {
    return STATUS_ACTIVE.equals(status);
  }
}
