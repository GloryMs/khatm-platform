package sy.khatm.platform.credential.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.domain.IssuanceIdempotency;

/**
 * Repository for {@link IssuanceIdempotency} rows (KH-2.8.2, spec FS-2.7a D6).
 *
 * <p>Module-private — only {@code IssuanceIdempotencyGuard} and the module's retention sweeper use
 * this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale.
 */
@Transactional(readOnly = true)
public interface IssuanceIdempotencyRepository extends JpaRepository<IssuanceIdempotency, UUID> {

  /**
   * Claim an {@code Idempotency-Key}: insert an {@code IN_PROGRESS} row unless one already exists
   * for the same (tenant, client, scope, key).
   *
   * <p>Native SQL on purpose (CONVENTIONS §5 — the one idempotency-invariant statement): {@code ON
   * CONFLICT DO NOTHING} lets the unique index {@code issuance_idem_key} decide the race without
   * aborting the caller's transaction. A concurrent insert of the same key blocks until the other
   * transaction commits or rolls back; it then either inserts (the other rolled back) or returns
   * {@code 0} (the other committed), and the caller reads the winner's row in the same transaction.
   * {@code issuerClientId} is passed as text so a {@code NULL} (human session) binds cleanly.
   *
   * @return {@code 1} when this caller claimed the key, {@code 0} when a row already held it
   */
  @Modifying
  @Transactional
  @Query(
      value =
          "INSERT INTO issuance_idempotency (id, tenant_id, issuer_client_id, idempotency_key,"
              + " scope, request_hash, state, created_at, expires_at)"
              + " VALUES (:id, :tenantId, CAST(:issuerClientId AS uuid), :idempotencyKey, :scope,"
              + " :requestHash, 'IN_PROGRESS', :createdAt, :expiresAt)"
              + " ON CONFLICT DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("tenantId") UUID tenantId,
      @Param("issuerClientId") String issuerClientId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("scope") String scope,
      @Param("requestHash") byte[] requestHash,
      @Param("createdAt") Instant createdAt,
      @Param("expiresAt") Instant expiresAt);

  /** The row an issuer client holds for {@code (scope, key)} in the current tenant (RLS). */
  Optional<IssuanceIdempotency> findByIssuerClientIdAndScopeAndIdempotencyKey(
      UUID issuerClientId, String scope, String idempotencyKey);

  /** The row the tenant's human sessions hold for {@code (scope, key)} (RLS-scoped). */
  Optional<IssuanceIdempotency> findByIssuerClientIdIsNullAndScopeAndIdempotencyKey(
      String scope, String idempotencyKey);

  /**
   * Delete every row whose retention elapsed (spec FS-2.7a D6, veto V5) — the worker-role {@code
   * IdempotencyRetentionSweeper}, run under system access (cross-tenant).
   *
   * @return how many rows were deleted
   */
  @Modifying
  @Transactional
  @Query("DELETE FROM IssuanceIdempotency i WHERE i.expiresAt < :now")
  int deleteExpired(@Param("now") Instant now);
}
