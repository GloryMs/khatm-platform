package sy.khatm.platform.status.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A compact, gzip-compressed Status List 2021-style revocation bitstring.
 *
 * <p>{@code nextIdx} is allocated atomically per credential via {@code
 * StatusListAllocatorService#allocate}, which holds a row lock across the read-increment-write so
 * that concurrent issuance never assigns the same bit twice. Publishing the signed bitstring
 * artifact to verifiers is KH-1.3.
 *
 * <p>This class is module-private; external code must depend on {@code status :: api} instead.
 */
@Entity
@Table(name = "status_list")
public class StatusList {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "list_code", nullable = false)
  private String listCode;

  @Column(nullable = false)
  private byte[] bitstring;

  @Column(nullable = false)
  private int capacity;

  @Column(name = "next_idx", nullable = false)
  private int nextIdx;

  @Column(nullable = false)
  private long version;

  @Column(name = "signed_artifact_ref")
  private String signedArtifactRef;

  @Column(name = "published_at")
  private Instant publishedAt;

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

  public String getListCode() {
    return listCode;
  }

  public void setListCode(String listCode) {
    this.listCode = listCode;
  }

  public byte[] getBitstring() {
    return bitstring;
  }

  public void setBitstring(byte[] bitstring) {
    this.bitstring = bitstring;
  }

  public int getCapacity() {
    return capacity;
  }

  public void setCapacity(int capacity) {
    this.capacity = capacity;
  }

  public int getNextIdx() {
    return nextIdx;
  }

  public void setNextIdx(int nextIdx) {
    this.nextIdx = nextIdx;
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
  }

  public String getSignedArtifactRef() {
    return signedArtifactRef;
  }

  public void setSignedArtifactRef(String signedArtifactRef) {
    this.signedArtifactRef = signedArtifactRef;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(Instant publishedAt) {
    this.publishedAt = publishedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
