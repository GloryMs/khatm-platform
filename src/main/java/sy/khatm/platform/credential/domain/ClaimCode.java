package sy.khatm.platform.credential.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A one-time code that hands a freshly issued credential to a wallet.
 *
 * <p>This is the sole exception to P1: {@code disclosuresEnc} carries encrypted disclosure values
 * for the short window between issuance and claim, and is zeroed ({@code null}) the moment the
 * claim succeeds or the code expires (spec FS-0.2 §3.7). Only {@code codeHash} (SHA-256) is stored
 * — the raw code is shown to the caller exactly once at creation and never persisted.
 *
 * <p>{@code disclosuresEnc} is AES-256-GCM encrypted by {@link
 * sy.khatm.platform.credential.domain.CredentialService#issueClaimCode} (spec FS-0.4 D7) and zeroed
 * by whichever of two paths reaches the row first: {@link
 * sy.khatm.platform.credential.domain.ClaimRedemptionService#redeem} on a successful wallet claim
 * (spec FS-1.2.1 D2), or {@link sy.khatm.platform.credential.worker.ClaimCodeExpiryWorker#sweep} on
 * an unclaimed code whose TTL elapsed (ADR-09). {@code SELECT ... FOR UPDATE} in {@link
 * sy.khatm.platform.credential.persistence.ClaimCodeRepository#findByCodeHashForUpdate} is what
 * makes these two paths race-safe against each other.
 *
 * <p>This class is module-private.
 */
@Entity
@Table(name = "claim_code")
public class ClaimCode {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "credential_id", nullable = false)
  private UUID credentialId;

  @Column(name = "code_hash", nullable = false, unique = true)
  private byte[] codeHash;

  @Column(name = "disclosures_enc")
  private byte[] disclosuresEnc;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "claimed_at")
  private Instant claimedAt;

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

  public UUID getCredentialId() {
    return credentialId;
  }

  public void setCredentialId(UUID credentialId) {
    this.credentialId = credentialId;
  }

  public byte[] getCodeHash() {
    return codeHash;
  }

  public void setCodeHash(byte[] codeHash) {
    this.codeHash = codeHash;
  }

  public byte[] getDisclosuresEnc() {
    return disclosuresEnc;
  }

  public void setDisclosuresEnc(byte[] disclosuresEnc) {
    this.disclosuresEnc = disclosuresEnc;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Instant getClaimedAt() {
    return claimedAt;
  }

  public void setClaimedAt(Instant claimedAt) {
    this.claimedAt = claimedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
