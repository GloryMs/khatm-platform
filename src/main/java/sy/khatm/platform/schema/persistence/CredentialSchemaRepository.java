package sy.khatm.platform.schema.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sy.khatm.platform.schema.domain.CredentialSchema;

/**
 * Repository for {@link CredentialSchema} entities.
 *
 * <p>Module-private — only {@code SchemaCatalogService} may use this.
 */
public interface CredentialSchemaRepository extends JpaRepository<CredentialSchema, UUID> {

  Optional<CredentialSchema> findByTenantIdAndCodeAndVersion(
      UUID tenantId, String code, int version);

  List<CredentialSchema> findAllByTenantId(UUID tenantId);

  /**
   * The schema's {@code default_validity} (a Postgres {@code interval}, unmapped on the entity
   * itself — see {@link CredentialSchema}'s Javadoc) as total seconds, or {@code null} if the
   * column is {@code NULL}.
   *
   * <p>A scalar {@code EXTRACT(epoch FROM ...)} read sidesteps mapping the native {@code interval}
   * JDBC type into any Java representation at all — {@code SchemaCatalogService} converts the
   * seconds into an ISO-8601 duration string for {@link
   * sy.khatm.platform.schema.api.SchemaDetail#defaultValidity}.
   */
  @Query(
      value =
          "SELECT EXTRACT(epoch FROM default_validity)::bigint FROM credential_schema WHERE id ="
              + " :id",
      nativeQuery = true)
  Long findDefaultValiditySeconds(@Param("id") UUID id);
}
