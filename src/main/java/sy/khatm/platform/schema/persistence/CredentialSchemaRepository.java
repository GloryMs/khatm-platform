package sy.khatm.platform.schema.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.schema.domain.CredentialSchema;

/**
 * Repository for {@link CredentialSchema} entities.
 *
 * <p>Module-private — only {@code SchemaCatalogService} may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale.
 */
@Transactional(readOnly = true)
public interface CredentialSchemaRepository extends JpaRepository<CredentialSchema, UUID> {

  Optional<CredentialSchema> findByTenantIdAndCodeAndVersion(
      UUID tenantId, String code, int version);

  List<CredentialSchema> findAllByTenantId(UUID tenantId);

  /** KH-1.1.1 — {@code GET /api/v1/schemas}'s optional {@code status} filter. */
  List<CredentialSchema> findAllByTenantIdAndStatus(UUID tenantId, String status);

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

  /**
   * Write {@code default_validity} (KH-1.1.1 schema authoring) — the write-side counterpart to
   * {@link #findDefaultValiditySeconds}, sidestepping the same native {@code interval} JDBC-type
   * mapping problem that column's Javadoc on {@link CredentialSchema} explains. {@code
   * make_interval(secs => NULL)} evaluates to {@code NULL}, so passing a {@code null} {@code
   * seconds} clears the column (a full-resource {@code PUT}/version body with no {@code
   * defaultValidity} means "no configured default," not "leave whatever was there before").
   */
  @Modifying
  @Transactional
  @Query(
      value =
          "UPDATE credential_schema SET default_validity = make_interval(secs => :seconds) WHERE"
              + " id = :id",
      nativeQuery = true)
  void updateDefaultValidity(@Param("id") UUID id, @Param("seconds") Long seconds);
}
