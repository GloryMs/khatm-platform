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
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;

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
  public List<SchemaSummary> listAll(String status) {
    UUID tenantId = TenantContext.current();
    List<CredentialSchema> rows =
        status == null
            ? schemas.findAllByTenantId(tenantId)
            : schemas.findAllByTenantIdAndStatus(tenantId, status);
    return rows.stream().map(SchemaCatalogService::toSummary).toList();
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
      CredentialSchema schema = existing.get();
      // KH-1.1.1: real schema authoring (create/publish/archive) exists now, so a resolved
      // existing row is no longer guaranteed PUBLISHED the way it always was when this method's
      // only callers were find-or-create ones (e.g. the demo seeder, which always created
      // PUBLISHED rows directly). Issuing against a DRAFT schema would sign credentials against
      // claim fields that might still change before publish; issuing against an ARCHIVED one
      // defeats the whole point of archiving (SEC/KH-1.1.1: archive stops NEW issuance).
      if (!"PUBLISHED".equals(schema.getStatus())) {
        throw new ConflictException(ErrorCode.KH_SCH_1409, "schema.invalid-transition");
      }
      return toRef(schema);
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

  static SchemaRef toRef(CredentialSchema schema) {
    return new SchemaRef(
        schema.getId(),
        schema.getCode(),
        schema.getVersion(),
        schema.getNameI18n(),
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

  static SchemaDetail toDetail(CredentialSchema schema, Long defaultValiditySeconds) {
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
