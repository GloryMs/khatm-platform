package sy.khatm.platform.rbac.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One TOTP recovery code (spec FS-2.2 V1) — 10 are minted on every successful {@code POST
 * /users/me/totp/confirm}, each individually consumable exactly once. Only the hash is stored (via
 * the same {@code PasswordEncoder} bean {@code UserAdminService} uses for temporary passwords —
 * these are already high-entropy random tokens, so the slower argon2id cost is a deliberate,
 * accepted trade-off for reusing one hashing scheme rather than introducing a second).
 *
 * <p>{@code usedAt} is set (never the row deleted) the moment a code is consumed, or when a reset/
 * re-enrollment invalidates every remaining unused code for the user — an {@code UPDATE}, not a
 * {@code DELETE} (spec V7's documented default: no {@code DELETE} grant without a reason).
 *
 * <p>This class is module-private; external code must depend on {@code rbac :: api} instead.
 */
@Entity
@Table(name = "user_totp_recovery_code")
public class UserTotpRecoveryCode {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "code_hash", nullable = false)
  private String codeHash;

  @Column(name = "used_at")
  private Instant usedAt;

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

  public UUID getUserId() {
    return userId;
  }

  public void setUserId(UUID userId) {
    this.userId = userId;
  }

  public String getCodeHash() {
    return codeHash;
  }

  public void setCodeHash(String codeHash) {
    this.codeHash = codeHash;
  }

  public Instant getUsedAt() {
    return usedAt;
  }

  public void setUsedAt(Instant usedAt) {
    this.usedAt = usedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
