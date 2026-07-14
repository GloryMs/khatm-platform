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
 * rule). The {@code signedJwt} column holds the compact JWS that travels on QR/NFC payloads.
 *
 * <p>This class is module-private; external code must not depend on it directly.
 */
@Entity
@Table(name = "credential")
public class Credential {

  @Id private UUID id;

  /** Human-readable reference, e.g. {@code CRE-2026-000341}. Unique across the system. */
  @Column(nullable = false, unique = true)
  private String ref;

  /** Credential type code, e.g. {@code CriminalRecordExtract/v1}. */
  @Column(name = "schema_code", nullable = false)
  private String schemaCode;

  /** Pseudonymous holder identifier. Never a real name or national ID (P1 rule). */
  @Column(name = "holder_ref")
  private String holderRef;

  /** Compact JWS carried by QR/NFC payloads. */
  @Column(name = "signed_jwt", columnDefinition = "text", nullable = false)
  private String signedJwt;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to", nullable = false)
  private Instant validTo;

  @Column(name = "max_uses", nullable = false)
  private int maxUses;

  @Column(name = "uses_remaining", nullable = false)
  private int usesRemaining;

  /** Index into the Status List 2021 bitstring (future KH-1.x). */
  @Column(name = "status_idx")
  private int statusIdx;

  @Column(nullable = false)
  private boolean revoked;

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

  public String getRef() {
    return ref;
  }

  public void setRef(String ref) {
    this.ref = ref;
  }

  public String getSchemaCode() {
    return schemaCode;
  }

  public void setSchemaCode(String schemaCode) {
    this.schemaCode = schemaCode;
  }

  public String getHolderRef() {
    return holderRef;
  }

  public void setHolderRef(String holderRef) {
    this.holderRef = holderRef;
  }

  public String getSignedJwt() {
    return signedJwt;
  }

  public void setSignedJwt(String signedJwt) {
    this.signedJwt = signedJwt;
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

  public int getStatusIdx() {
    return statusIdx;
  }

  public void setStatusIdx(int statusIdx) {
    this.statusIdx = statusIdx;
  }

  public boolean isRevoked() {
    return revoked;
  }

  public void setRevoked(boolean revoked) {
    this.revoked = revoked;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
