package sy.khatm.platform.issuerclient.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Module-private persistence for {@link IssuerClient} and its schema allowlist. */
@Transactional(readOnly = true)
interface IssuerClientRepository extends JpaRepository<IssuerClient, UUID> {

  Optional<IssuerClient> findByKeyPrefix(String keyPrefix);

  List<IssuerClient> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  @Query("select c from IssuerClient c where c.status = 'RETIRING' and c.retireAfter <= :now")
  List<IssuerClient> findRetiringDue(@Param("now") Instant now);

  /**
   * Stamp {@code last_used_at} at most once per throttle window — a plain conditional UPDATE, so
   * authentication never turns into a write per request.
   */
  @Modifying
  @Transactional
  @Query(
      "update IssuerClient c set c.lastUsedAt = :now where c.id = :id"
          + " and (c.lastUsedAt is null or c.lastUsedAt < :threshold)")
  int touchLastUsed(
      @Param("id") UUID id, @Param("now") Instant now, @Param("threshold") Instant threshold);

  @Query(
      value =
          "SELECT EXISTS (SELECT 1 FROM issuer_client_schema"
              + " WHERE issuer_client_id = :clientId AND schema_id = :schemaId)",
      nativeQuery = true)
  boolean existsAllowedSchema(@Param("clientId") UUID clientId, @Param("schemaId") UUID schemaId);

  @Query(
      value =
          "SELECT schema_id FROM issuer_client_schema WHERE issuer_client_id = :clientId"
              + " ORDER BY schema_id",
      nativeQuery = true)
  List<UUID> findAllowedSchemaIds(@Param("clientId") UUID clientId);

  @Modifying
  @Transactional
  @Query(
      value =
          "INSERT INTO issuer_client_schema (issuer_client_id, schema_id, tenant_id)"
              + " SELECT :clientId, :schemaId, tenant_id FROM issuer_client WHERE id = :clientId"
              + " ON CONFLICT DO NOTHING",
      nativeQuery = true)
  void insertAllowedSchema(@Param("clientId") UUID clientId, @Param("schemaId") UUID schemaId);
}
