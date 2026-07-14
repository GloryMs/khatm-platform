package sy.khatm.platform.schema.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaDefinition;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.schema.persistence.CredentialSchemaRepository;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;

/**
 * Default {@link SchemaCatalog} implementation.
 *
 * <p>This class is module-private. External code must depend on {@link SchemaCatalog}, not this
 * class.
 */
@Service
class SchemaCatalogService implements SchemaCatalog {

  private final CredentialSchemaRepository schemas;

  SchemaCatalogService(CredentialSchemaRepository schemas) {
    this.schemas = schemas;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SchemaRef> findById(UUID id) {
    return schemas.findById(id).map(SchemaCatalogService::toRef);
  }

  @Override
  @Transactional
  public SchemaRef ensurePublished(SchemaDefinition definition) {
    UUID tenantId = TenantContext.current();
    Optional<CredentialSchema> existing =
        schemas.findByTenantIdAndCodeAndVersion(tenantId, definition.code(), definition.version());
    if (existing.isPresent()) {
      return toRef(existing.get());
    }

    Instant now = Instant.now();
    CredentialSchema schema = new CredentialSchema();
    schema.setId(Uuidv7.generate());
    schema.setTenantId(tenantId);
    schema.setCode(definition.code());
    schema.setVersion(definition.version());
    schema.setNameI18n(definition.nameI18n());
    schema.setClaimsDefJson(definition.claimsDefJson());
    schema.setSdFields(definition.sdFields().toArray(new String[0]));
    schema.setDefaultMaxUses(definition.defaultMaxUses());
    schema.setStatus("PUBLISHED");
    schema.setCreatedAt(now);
    schema.setUpdatedAt(now);
    schemas.save(schema);
    return toRef(schema);
  }

  private static SchemaRef toRef(CredentialSchema schema) {
    return new SchemaRef(schema.getId(), schema.getCode(), schema.getVersion());
  }
}
