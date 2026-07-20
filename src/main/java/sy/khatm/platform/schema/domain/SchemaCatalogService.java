package sy.khatm.platform.schema.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaDefinition;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.schema.api.SchemaSummary;
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
  @Transactional(readOnly = true)
  public List<SchemaSummary> listAll() {
    return schemas.findAllByTenantId(TenantContext.current()).stream()
        .map(SchemaCatalogService::toSummary)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SchemaDetail> findDetailById(UUID id) {
    return schemas
        .findById(id)
        .map(schema -> toDetail(schema, schemas.findDefaultValiditySeconds(id)));
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
    return new SchemaRef(
        schema.getId(),
        schema.getCode(),
        schema.getVersion(),
        schema.getClaimsDefJson(),
        List.of(schema.getSdFields()));
  }

  private static SchemaSummary toSummary(CredentialSchema schema) {
    return new SchemaSummary(
        schema.getId(),
        schema.getCode(),
        schema.getNameI18n(),
        schema.getVersion(),
        schema.getStatus());
  }

  private static SchemaDetail toDetail(CredentialSchema schema, Long defaultValiditySeconds) {
    return new SchemaDetail(
        schema.getId(),
        schema.getCode(),
        schema.getNameI18n(),
        schema.getVersion(),
        schema.getStatus(),
        schema.getClaimsDefJson(),
        List.of(schema.getSdFields()),
        schema.getDefaultMaxUses(),
        toIso8601Duration(defaultValiditySeconds));
  }

  /**
   * Render a total-seconds interval as an ISO-8601 duration string — {@code "P{n}D"} for a
   * whole-day interval (the common case for a schema's default validity, e.g. {@code "P90D"}),
   * falling back to {@link Duration#toString()}'s {@code PT}-based form for anything finer-grained.
   * Both forms are valid ISO-8601; there is no single Java type that renders every interval in the
   * calendar-style {@code PnD} form, so this picks the readable form when it applies exactly.
   */
  private static String toIso8601Duration(Long totalSeconds) {
    if (totalSeconds == null) {
      return null;
    }
    if (totalSeconds % 86400 == 0) {
      return "P" + (totalSeconds / 86400) + "D";
    }
    return Duration.ofSeconds(totalSeconds).toString();
  }
}
