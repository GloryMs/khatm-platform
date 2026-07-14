package sy.khatm.platform.credential.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root representing an issued verifiable credential.
 *
 * <p>Stores only cryptographic proofs and status metadata — never document content or PII (P1
 * rule). {@code signedPayload} holds the compact SD-JWT (digests only, no disclosed values) that
 * travels on QR/NFC payloads; {@code payloadHash} is its SHA-256, reserved as a future Merkle leaf
 * (Phase 3).
 *
 * <p>This class is module-private; external code must not depend on it directly.
 */
@Entity
@Table(name = "credential")
public class Credential {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "schema_id", nullable = false)
  private UUID schemaId;

  @Column(name = "holder_id", nullable = false)
  private UUID holderId;

  /** Human-readable reference, e.g. {@code CRE-2026-000341}. Unique across the system. */
  @Column(nullable = false, unique = true)
  private String ref;

  /** Id of the original credential this row is a re-issued copy of, if any. */
  @Column(name = "copy_of")
  private UUID copyOf;

  /** Compact SD-JWT carried by QR/NFC payloads — digests only, never disclosed claim values. */
  @Column(name = "signed_payload", columnDefinition = "text", nullable = false)
  private String signedPayload;

  @Column(name = "payload_hash", nullable = false)
  private byte[] payloadHash;

  @Column(name = "status_list_id", nullable = false)
  private UUID statusListId;

  @Column(name = "status_idx", nullable = false)
  private int statusIdx;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to", nullable = false)
  private Instant validTo;

  @Column(name = "max_uses", nullable = false)
  private int maxUses;

  @Column(name = "uses_remaining", nullable = false)
  private int usesRemaining;

  @Column(nullable = false)
  private boolean revoked;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  /** User or API key that issued this credential (audit trail); {@code null} until KH-0.6. */
  @Column(name = "issued_by")
  private UUID issuedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  // Accessors — public so sub-packages within this module can read/write them.
  // Modulith enforces that external modules cannot import this class at all.

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

  public UUID getSchemaId() {
    return schemaId;
  }

  public void setSchemaId(UUID schemaId) {
    this.schemaId = schemaId;
  }

  public UUID getHolderId() {
    return holderId;
  }

  public void setHolderId(UUID holderId) {
    this.holderId = holderId;
  }

  public String getRef() {
    return ref;
  }

  public void setRef(String ref) {
    this.ref = ref;
  }

  public UUID getCopyOf() {
    return copyOf;
  }

  public void setCopyOf(UUID copyOf) {
    this.copyOf = copyOf;
  }

  public String getSignedPayload() {
    return signedPayload;
  }

  public void setSignedPayload(String signedPayload) {
    this.signedPayload = signedPayload;
  }

  public byte[] getPayloadHash() {
    return payloadHash;
  }

  public void setPayloadHash(byte[] payloadHash) {
    this.payloadHash = payloadHash;
  }

  public UUID getStatusListId() {
    return statusListId;
  }

  public void setStatusListId(UUID statusListId) {
    this.statusListId = statusListId;
  }

  public int getStatusIdx() {
    return statusIdx;
  }

  public void setStatusIdx(int statusIdx) {
    this.statusIdx = statusIdx;
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

  public int getMaxUses() {
    return maxUses;
  }

  public void setMaxUses(int maxUses) {
    this.maxUses = maxUses;
  }

  public int getUsesRemaining() {
    return usesRemaining;
  }

  public void setUsesRemaining(int usesRemaining) {
    this.usesRemaining = usesRemaining;
  }

  public boolean isRevoked() {
    return revoked;
  }

  public void setRevoked(boolean revoked) {
    this.revoked = revoked;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(Instant revokedAt) {
    this.revokedAt = revokedAt;
  }

  public UUID getIssuedBy() {
    return issuedBy;
  }

  public void setIssuedBy(UUID issuedBy) {
    this.issuedBy = issuedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
