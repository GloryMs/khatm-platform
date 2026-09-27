package sy.khatm.platform.credential.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * One {@code Idempotency-Key} record for an issuance request (KH-2.8.2, spec FS-2.7a D6).
 *
 * <p>Rows are only ever inserted by {@link
 * sy.khatm.platform.credential.persistence.IssuanceIdempotencyRepository#insertIfAbsent} (a native
 * {@code INSERT ... ON CONFLICT DO NOTHING}), never by {@code save()} of a new instance, so the
 * unique index resolves every concurrent retry race. This entity is used to read a row back and to
 * move it to {@link State#DONE}.
 *
 * <p>Proofs only (P1): {@code requestHash} is the SHA-256 of the canonical request body, and {@code
 * resultSnapshot} (bulk only) carries per-row indices, statuses, ids, refs, and error codes. Never
 * a claim code, a claim value, or the request body itself.
 *
 * <p>This class is module-private.
 */
@Entity
@Table(name = "issuance_idempotency")
public class IssuanceIdempotency {

  /** Which issuance endpoint a key belongs to; the same key may be used once per scope. */
  public enum Scope {
    ISSUE,
    BULK
  }

  /** {@code IN_PROGRESS} until the issuance commits; a bulk row is visible in this state. */
  public enum State {
    IN_PROGRESS,
    DONE
  }

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "issuer_client_id")
  private UUID issuerClientId;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(name = "scope", nullable = false)
  private String scope;

  @Column(name = "request_hash", nullable = false)
  private byte[] requestHash;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "credential_id")
  private UUID credentialId;

  @Column(name = "result_snapshot", columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String resultSnapshot;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getIssuerClientId() {
    return issuerClientId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public Scope getScope() {
    return Scope.valueOf(scope);
  }

  public byte[] getRequestHash() {
    return requestHash;
  }

  public State getState() {
    return State.valueOf(state);
  }

  public UUID getCredentialId() {
    return credentialId;
  }

  public String getResultSnapshot() {
    return resultSnapshot;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  /** Marks an {@code ISSUE} row finished, pointing at the credential it issued. */
  public void completeIssue(UUID issuedCredentialId) {
    this.credentialId = issuedCredentialId;
    this.state = State.DONE.name();
  }

  /** Marks a {@code BULK} row finished with its code-free per-row result snapshot. */
  public void completeBulk(String snapshotJson) {
    this.resultSnapshot = snapshotJson;
    this.state = State.DONE.name();
  }
}
