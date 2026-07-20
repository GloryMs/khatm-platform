package sy.khatm.platform.consumer.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sy.khatm.platform.consumer.domain.ConsumingParty;

/**
 * Repository for {@link ConsumingParty} entities, plus the {@code consuming_party_schema}
 * join-table operations schema scoping needs (KH-1.4.3) — there is no JPA entity for {@code
 * consuming_party_schema} itself, the same bare-composite-key-join-table treatment {@code
 * rbac.persistence.RoleRepository} gives {@code user_role}.
 *
 * <p>Module-private — only {@code ConsumingPartyRegistryService} may use this.
 */
public interface ConsumingPartyRepository extends JpaRepository<ConsumingParty, UUID> {

  /**
   * Whether {@code (partyId, schemaId)} has a row in {@code consuming_party_schema} — the
   * deny-by-default schema-scoping check {@code /consume} runs before its atomic update.
   */
  @Query(
      value =
          "SELECT EXISTS (SELECT 1 FROM consuming_party_schema"
              + " WHERE consuming_party_id = :partyId AND schema_id = :schemaId)",
      nativeQuery = true)
  boolean existsAllowedSchema(@Param("partyId") UUID partyId, @Param("schemaId") UUID schemaId);

  /** Insert an {@code (partyId, schemaId)} allowlist row; idempotent (no-op if already present). */
  @Modifying
  @Query(
      value =
          "INSERT INTO consuming_party_schema (consuming_party_id, schema_id)"
              + " VALUES (:partyId, :schemaId) ON CONFLICT DO NOTHING",
      nativeQuery = true)
  void insertAllowedSchema(@Param("partyId") UUID partyId, @Param("schemaId") UUID schemaId);
}
