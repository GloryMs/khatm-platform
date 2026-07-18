package sy.khatm.platform.schema.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
