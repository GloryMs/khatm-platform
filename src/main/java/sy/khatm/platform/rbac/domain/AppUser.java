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
 * <p><b>KH-2.2b — {@code mustChangePassword}:</b> set when a user is provisioned with a temporary
 * password (D5 create / reset-password, D6 onboarding's first admin) and cleared the moment they
 * set a real password via {@code POST /api/v1/users/me/password}. While set, every authenticated
 * call except that one endpoint is rejected with {@code 403 KH-USR-0403} by {@code
 * rbac.security.PasswordChangeEnforcementFilter} (read live per request — the flag can flip
 * mid-session on an admin password reset, and the spec's guarantee is that the very next call is
 * blocked, so the value is never cached in the session principal).
 *
 * <p><b>KH-2.2c — TOTP (spec FS-2.2 V1):</b> {@code totpSecretEnc} is AES-256-GCM ciphertext
 * ({@link TotpSecretEncryptionService}), never plaintext at rest. {@code totpEnrolledAt} is set on
 * every {@code POST /users/me/totp/enroll} (re)generation; {@code totpConfirmedAt} is set on {@code
 * POST /users/me/totp/confirm} and is {@code null} until then — {@code null} means "no active TOTP"
 * for both the login challenge and the mandatory-enrollment gate ({@code
 * rbac.security.TotpEnrollmentEnforcementFilter}, read live per request for the identical
 * mid-session-change reason as {@code mustChangePassword}).
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

  @Column(name = "must_change_password", nullable = false)
  private boolean mustChangePassword;

  @Column(name = "totp_secret_enc")
  private byte[] totpSecretEnc;

  @Column(name = "totp_enrolled_at")
  private Instant totpEnrolledAt;

  @Column(name = "totp_confirmed_at")
  private Instant totpConfirmedAt;

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

  public boolean isMustChangePassword() {
    return mustChangePassword;
  }

  public void setMustChangePassword(boolean mustChangePassword) {
    this.mustChangePassword = mustChangePassword;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public byte[] getTotpSecretEnc() {
    return totpSecretEnc;
  }

  public void setTotpSecretEnc(byte[] totpSecretEnc) {
    this.totpSecretEnc = totpSecretEnc;
  }

  public Instant getTotpEnrolledAt() {
    return totpEnrolledAt;
  }

  public void setTotpEnrolledAt(Instant totpEnrolledAt) {
    this.totpEnrolledAt = totpEnrolledAt;
  }

  public Instant getTotpConfirmedAt() {
    return totpConfirmedAt;
  }

  public void setTotpConfirmedAt(Instant totpConfirmedAt) {
    this.totpConfirmedAt = totpConfirmedAt;
  }

  boolean isActive() {
    return STATUS_ACTIVE.equals(status);
  }
}
