package sy.khatm.platform.consumer.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.consumer.domain.ConsumingParty;

/**
 * Repository for {@link ConsumingParty} entities, plus the {@code consuming_party_schema}
 * join-table operations schema scoping needs (KH-1.4.3/KH-1.4.4) — there is no JPA entity for
 * {@code consuming_party_schema} itself, the same bare-composite-key-join-table treatment {@code
 * rbac.persistence.RoleRepository} gives {@code user_role}.
 *
 * <p>Module-private — only the {@code consumer.domain} services may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale. {@link
 * #existsAllowedSchema} in particular is called from {@code
 * ConsumingPartyRegistryService#isSchemaAllowed}, itself called from {@code
 * CredentialService#enforceSchemaAllowlist} — deliberately non-{@code @Transactional} (see {@code
 * CredentialRepository}'s Javadoc).
 */
@Transactional(readOnly = true)
public interface ConsumingPartyRepository extends JpaRepository<ConsumingParty, UUID> {

  /** Every party registered for a tenant, newest first (KH-1.4.4 admin list). */
  List<ConsumingParty> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

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

  /** The schema ids on a party's allowlist (KH-1.4.4 admin view). */
  @Query(
      value =
          "SELECT schema_id FROM consuming_party_schema WHERE consuming_party_id = :partyId"
              + " ORDER BY schema_id",
      nativeQuery = true)
  List<UUID> findAllowedSchemaIds(@Param("partyId") UUID partyId);

  /**
   * Insert an {@code (partyId, schemaId)} allowlist row; idempotent (no-op if already present).
   *
   * <p>{@code tenant_id} (KH-2.1, spec FS-2.1 D2 — backfilled onto this join table by {@code
   * V7__rls_policies.sql}) is derived from the party's own row rather than passed in, so this
   * method's signature and every caller stay unchanged.
   */
  @Modifying
  @Transactional
  @Query(
      value =
          "INSERT INTO consuming_party_schema (consuming_party_id, schema_id, tenant_id)"
              + " SELECT :partyId, :schemaId, tenant_id FROM consuming_party WHERE id = :partyId"
              + " ON CONFLICT DO NOTHING",
      nativeQuery = true)
  void insertAllowedSchema(@Param("partyId") UUID partyId, @Param("schemaId") UUID schemaId);

  /**
   * Remove an {@code (partyId, schemaId)} allowlist row (KH-1.4.4 D5); idempotent.
   *
   * @return the number of rows removed — {@code 0} if the pair was not allowed
   */
  @Modifying
  @Transactional
  @Query(
      value =
          "DELETE FROM consuming_party_schema"
              + " WHERE consuming_party_id = :partyId AND schema_id = :schemaId",
      nativeQuery = true)
  int deleteAllowedSchema(@Param("partyId") UUID partyId, @Param("schemaId") UUID schemaId);
}
