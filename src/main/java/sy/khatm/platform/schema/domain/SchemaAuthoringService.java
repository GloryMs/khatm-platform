package sy.khatm.platform.schema.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.schema.api.ClaimFieldRequest;
import sy.khatm.platform.schema.api.SchemaAuthoringRequest;
import sy.khatm.platform.schema.api.SchemaCreateRequest;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.schema.persistence.CredentialSchemaRepository;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;

/**
 * Schema authoring workflow (KH-1.1.1): create a {@code DRAFT}, edit it in place, publish it (the
 * immutability line — a {@code PUBLISHED} schema's claim fields can never change again), version
 * it, or archive it (stops new issuance; existing credentials/verification are unaffected).
 *
 * <p>This class is module-private. {@code schema.web.SchemaController} in the same module may
 * reference it directly; external code must wait for a published {@code schema :: api} authoring
 * surface, which does not exist yet — every other module still only needs {@link
 * sy.khatm.platform.schema.api.SchemaCatalog}'s read/find-or-create surface.
 *
 * <p><b>Claim field types:</b> {@code text}, {@code number}, {@code date} — the set {@link
 * #SUPPORTED_CLAIM_TYPES} enforces. Nothing downstream ({@code CredentialService#verify}'s {@code
 * mandatoryFields}) currently reads a claim's {@code type} at all — it exists for the console's
 * form rendering, not platform enforcement — but an unbounded free-text type would let an authoring
 * mistake reach {@code claims_def} silently, so it is validated here regardless.
 */
@Service
public class SchemaAuthoringService {

  private static final Set<String> SUPPORTED_CLAIM_TYPES = Set.of("text", "number", "date");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final CredentialSchemaRepository schemas;
  private final AuditService audit;

  SchemaAuthoringService(CredentialSchemaRepository schemas, AuditService audit) {
    this.schemas = schemas;
    this.audit = audit;
  }

  /**
   * Create a new {@code DRAFT} schema at version {@code 1}.
   *
   * @throws ValidationException {@link ErrorCode#KH_SCH_0400} if the request fails a business-level
   *     check (see {@link SchemaCreateRequest}'s Javadoc), including a {@code code} already
   *     registered for this tenant at version 1
   */
  @Transactional
  public SchemaDetail create(SchemaCreateRequest req) {
    UUID tenantId = TenantContext.current();
    if (schemas.findByTenantIdAndCodeAndVersion(tenantId, req.code(), 1).isPresent()) {
      throw invalid("code \"" + req.code() + "\" is already registered at version 1");
    }
    validateAuthoringFields(req.nameI18n(), req.claimsDef(), req.sdFields());
    int maxUses = requireValidMaxUses(req.defaultMaxUses());
    Long validitySeconds = parseValiditySeconds(req.defaultValidity());

    Instant now = Instant.now();
    CredentialSchema schema = new CredentialSchema();
    schema.setId(Uuidv7.generate());
    schema.setTenantId(tenantId);
    schema.setCode(req.code());
    schema.setVersion(1);
    applyAuthoringFields(schema, req.nameI18n(), req.claimsDef(), req.sdFields(), maxUses);
    schema.setStatus("DRAFT");
    schema.setCreatedAt(now);
    schema.setUpdatedAt(now);
    schemas.save(schema);
    schemas.updateDefaultValidity(schema.getId(), validitySeconds);

    audit.record(AuditAction.SCHEMA_CREATED, "credential_schema", ref(schema), null);
    return SchemaCatalogService.toDetail(schema, validitySeconds);
  }

  /**
   * Rewrite a {@code DRAFT} schema's authoring fields in place — fixing mistakes before publish.
   *
   * @throws NotFoundException {@link ErrorCode#KH_SCH_0404} if no schema with this id exists
   * @throws ConflictException {@link ErrorCode#KH_SCH_0409} if the schema is not {@code DRAFT}
   * @throws ValidationException {@link ErrorCode#KH_SCH_0400} if the request fails a business-level
   *     check (see {@link SchemaAuthoringRequest}'s Javadoc)
   */
  @Transactional
  public SchemaDetail update(UUID id, SchemaAuthoringRequest req) {
    CredentialSchema schema = findOrThrow(id);
    if (!"DRAFT".equals(schema.getStatus())) {
      throw new ConflictException(ErrorCode.KH_SCH_0409, "schema.immutable-after-publish");
    }
    validateAuthoringFields(req.nameI18n(), req.claimsDef(), req.sdFields());
    int maxUses = requireValidMaxUses(req.defaultMaxUses());
    Long validitySeconds = parseValiditySeconds(req.defaultValidity());

    applyAuthoringFields(schema, req.nameI18n(), req.claimsDef(), req.sdFields(), maxUses);
    schema.setUpdatedAt(Instant.now());
    schemas.save(schema);
    schemas.updateDefaultValidity(id, validitySeconds);

    audit.record(AuditAction.SCHEMA_UPDATED, "credential_schema", ref(schema), null);
    return SchemaCatalogService.toDetail(schema, validitySeconds);
  }

  /**
   * Publish a {@code DRAFT} schema — from here its claim fields are immutable; a mistake found
   * later needs a new version ({@link #createVersion}), not an edit.
   *
   * @throws NotFoundException {@link ErrorCode#KH_SCH_0404} if no schema with this id exists
   * @throws ConflictException {@link ErrorCode#KH_SCH_1409} if the schema is not {@code DRAFT}
   */
  @Transactional
  public SchemaDetail publish(UUID id) {
    CredentialSchema schema = findOrThrow(id);
    if (!"DRAFT".equals(schema.getStatus())) {
      throw new ConflictException(ErrorCode.KH_SCH_1409, "schema.invalid-transition");
    }
    schema.setStatus("PUBLISHED");
    schema.setUpdatedAt(Instant.now());
    schemas.save(schema);

    audit.record(AuditAction.SCHEMA_PUBLISHED, "credential_schema", ref(schema), null);
    return SchemaCatalogService.toDetail(schema, schemas.findDefaultValiditySeconds(id));
  }

  /**
   * Create a new {@code DRAFT} version of a {@code PUBLISHED} schema — same {@code code}, version
   * {@code + 1}. The console is responsible for prefilling {@code req} from the source schema's
   * current fields; this method validates the submitted body exactly like {@link #create}, with no
   * server-side default-merging.
   *
   * @throws NotFoundException {@link ErrorCode#KH_SCH_0404} if no schema with this id exists
   * @throws ConflictException {@link ErrorCode#KH_SCH_1409} if the source schema is not {@code
   *     PUBLISHED}
   * @throws ValidationException {@link ErrorCode#KH_SCH_0400} if the request fails a business-level
   *     check (see {@link SchemaAuthoringRequest}'s Javadoc)
   */
  @Transactional
  public SchemaDetail createVersion(UUID id, SchemaAuthoringRequest req) {
    CredentialSchema source = findOrThrow(id);
    if (!"PUBLISHED".equals(source.getStatus())) {
      throw new ConflictException(ErrorCode.KH_SCH_1409, "schema.invalid-transition");
    }
    validateAuthoringFields(req.nameI18n(), req.claimsDef(), req.sdFields());
    int maxUses = requireValidMaxUses(req.defaultMaxUses());
    Long validitySeconds = parseValiditySeconds(req.defaultValidity());

    Instant now = Instant.now();
    CredentialSchema version = new CredentialSchema();
    version.setId(Uuidv7.generate());
    version.setTenantId(source.getTenantId());
    version.setCode(source.getCode());
    version.setVersion(source.getVersion() + 1);
    applyAuthoringFields(version, req.nameI18n(), req.claimsDef(), req.sdFields(), maxUses);
    version.setStatus("DRAFT");
    version.setCreatedAt(now);
    version.setUpdatedAt(now);
    schemas.save(version);
    schemas.updateDefaultValidity(version.getId(), validitySeconds);

    audit.record(AuditAction.SCHEMA_VERSION_CREATED, "credential_schema", ref(version), null);
    return SchemaCatalogService.toDetail(version, validitySeconds);
  }

  /**
   * Archive a {@code PUBLISHED} schema — stops new issuance ({@code CredentialService#issue}/{@code
   * SchemaCatalog#ensurePublished} reject it, {@link ErrorCode#KH_SCH_1409}) but leaves every
   * already-issued credential and its verification/ consumption completely unaffected; nothing
   * about a credential's validity depends on its schema's current lifecycle status.
   *
   * @throws NotFoundException {@link ErrorCode#KH_SCH_0404} if no schema with this id exists
   * @throws ConflictException {@link ErrorCode#KH_SCH_1409} if the schema is not {@code PUBLISHED}
   */
  @Transactional
  public SchemaDetail archive(UUID id) {
    CredentialSchema schema = findOrThrow(id);
    if (!"PUBLISHED".equals(schema.getStatus())) {
      throw new ConflictException(ErrorCode.KH_SCH_1409, "schema.invalid-transition");
    }
    schema.setStatus("ARCHIVED");
    schema.setUpdatedAt(Instant.now());
    schemas.save(schema);

    audit.record(AuditAction.SCHEMA_ARCHIVED, "credential_schema", ref(schema), null);
    return SchemaCatalogService.toDetail(schema, schemas.findDefaultValiditySeconds(id));
  }

  private CredentialSchema findOrThrow(UUID id) {
    return schemas
        .findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.KH_SCH_0404, "schema.not-found", id));
  }

  private static void applyAuthoringFields(
      CredentialSchema schema,
      Map<String, String> nameI18n,
      List<ClaimFieldRequest> claimsDef,
      List<String> sdFields,
      int maxUses) {
    schema.setNameI18n(toLocalizedText(nameI18n));
    List<String> sd = sdFields == null ? List.of() : sdFields;
    schema.setClaimsDefJson(claimsDefJson(claimsDef, sd));
    schema.setSdFields(sd.toArray(new String[0]));
    schema.setDefaultMaxUses(maxUses);
  }

  /**
   * @throws ValidationException {@link ErrorCode#KH_SCH_0400} on the first rule that fails: {@code
   *     nameI18n} missing {@code en}/{@code ar}, a claim field with a blank name, a duplicate claim
   *     field name, an unsupported claim field {@code type}, a claim field's {@code labelI18n}
   *     missing {@code en}/{@code ar}, or an {@code sdFields} entry not among the claim field names
   */
  private static void validateAuthoringFields(
      Map<String, String> nameI18n, List<ClaimFieldRequest> claimsDef, List<String> sdFields) {
    requireBilingual(nameI18n, "nameI18n");
    if (claimsDef == null || claimsDef.isEmpty()) {
      throw invalid("claimsDef must not be empty");
    }

    Set<String> names = new LinkedHashSet<>();
    for (ClaimFieldRequest field : claimsDef) {
      if (field.name() == null || field.name().isBlank()) {
        throw invalid("a claim field name must not be blank");
      }
      if (!names.add(field.name())) {
        throw invalid("duplicate claim field name \"" + field.name() + "\"");
      }
      if (!SUPPORTED_CLAIM_TYPES.contains(field.type())) {
        throw invalid(
            "claim field \""
                + field.name()
                + "\" has unsupported type \""
                + field.type()
                + "\" (expected one of "
                + SUPPORTED_CLAIM_TYPES
                + ")");
      }
      requireBilingual(field.labelI18n(), "claimsDef[\"" + field.name() + "\"].labelI18n");
    }

    if (sdFields != null) {
      for (String sd : sdFields) {
        if (!names.contains(sd)) {
          throw invalid("sdFields entry \"" + sd + "\" is not a defined claim field");
        }
      }
    }
  }

  private static void requireBilingual(Map<String, String> value, String fieldName) {
    if (value == null || isBlank(value.get("en")) || isBlank(value.get("ar"))) {
      throw invalid(fieldName + " requires both a non-blank \"en\" and \"ar\" value");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static int requireValidMaxUses(Integer requested) {
    int maxUses = requested == null ? 1 : requested;
    if (maxUses < 1) {
      throw invalid("defaultMaxUses must be >= 1");
    }
    return maxUses;
  }

  /**
   * @throws ValidationException {@link ErrorCode#KH_SCH_0400} if {@code iso} is non-blank but not a
   *     valid ISO-8601 duration
   */
  private static Long parseValiditySeconds(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Duration.parse(iso).getSeconds();
    } catch (DateTimeParseException e) {
      throw invalid("defaultValidity \"" + iso + "\" is not a valid ISO-8601 duration");
    }
  }

  private static ValidationException invalid(String reason) {
    return new ValidationException(ErrorCode.KH_SCH_0400, "schema.validation-failed", reason);
  }

  private static LocalizedText toLocalizedText(Map<String, String> value) {
    return new LocalizedText(value.get("en"), value.get("ar"));
  }

  /** {@code code:version} — the entity_ref shape every {@code SCHEMA_*} audit action uses. */
  private static String ref(CredentialSchema schema) {
    return schema.getCode() + ":" + schema.getVersion();
  }

  private static String claimsDefJson(List<ClaimFieldRequest> claimsDef, List<String> sdFields) {
    Set<String> withholdable = new LinkedHashSet<>(sdFields);
    ObjectNode root = JSON.createObjectNode();
    for (ClaimFieldRequest field : claimsDef) {
      ObjectNode fieldNode = root.putObject(field.name());
      fieldNode.put("type", field.type());
      fieldNode.put("required", !withholdable.contains(field.name()));
      ObjectNode label = fieldNode.putObject("label_i18n");
      label.put("en", field.labelI18n().get("en"));
      label.put("ar", field.labelI18n().get("ar"));
    }
    return root.toString();
  }
}
