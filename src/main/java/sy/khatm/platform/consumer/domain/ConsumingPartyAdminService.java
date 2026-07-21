package sy.khatm.platform.consumer.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.consumer.api.ConsumingPartyAdmin;
import sy.khatm.platform.consumer.api.ConsumingPartyView;
import sy.khatm.platform.consumer.persistence.ConsumingPartyRepository;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;

/**
 * Default {@link ConsumingPartyAdmin} implementation — the consuming-party admin plane (KH-1.4.4,
 * {@code /api/v1/admin/consuming-parties}).
 *
 * <p>This class is module-private. External code must depend on {@link ConsumingPartyAdmin}, not
 * this class.
 */
@Service
class ConsumingPartyAdminService implements ConsumingPartyAdmin {

  /** Lowercase slug: 2–63 chars, starting alphanumeric (KH-1.4.4 D2). */
  private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-_]{1,62}$");

  private final ConsumingPartyRepository consumingParties;
  private final SchemaCatalog schemas;
  private final AuditService audit;

  ConsumingPartyAdminService(
      ConsumingPartyRepository consumingParties, SchemaCatalog schemas, AuditService audit) {
    this.consumingParties = consumingParties;
    this.schemas = schemas;
    this.audit = audit;
  }

  @Override
  @Transactional
  public ConsumingPartyView create(String code, LocalizedText nameI18n) {
    if (code == null || !CODE_PATTERN.matcher(code).matches()) {
      throw new ValidationException(ErrorCode.KH_CNS_0400, "consumer.invalid-code");
    }
    UUID tenantId = TenantContext.current();
    UUID id = ConsumingPartyIds.deterministicId(tenantId, code);
    if (consumingParties.findById(id).isPresent()) {
      throw new ConflictException(ErrorCode.KH_CNS_0409, "consumer.duplicate-code");
    }

    ConsumingParty party = new ConsumingParty();
    party.setId(id);
    party.setTenantId(tenantId);
    party.setCode(code);
    party.setNameI18n(nameI18n);
    party.setStatus(ConsumingParty.STATUS_ACTIVE);
    party.setCreatedAt(Instant.now());
    try {
      consumingParties.saveAndFlush(party);
    } catch (DataIntegrityViolationException raced) {
      // Concurrent create of the same brand-new code: the deterministic id collided. Report it the
      // same way as the pre-check above — a duplicate, never a second row.
      throw new ConflictException(ErrorCode.KH_CNS_0409, "consumer.duplicate-code");
    }

    audit.record(AuditAction.CONSUMING_PARTY_CREATED, "consuming_party", code, null);
    return toView(party);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConsumingPartyView> list() {
    return consumingParties.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.current()).stream()
        .map(this::toView)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public ConsumingPartyView get(UUID id) {
    return toView(require(id));
  }

  @Override
  @Transactional
  public ConsumingPartyView suspend(UUID id) {
    return flipStatus(id, ConsumingParty.STATUS_SUSPENDED, AuditAction.CONSUMING_PARTY_SUSPENDED);
  }

  @Override
  @Transactional
  public ConsumingPartyView activate(UUID id) {
    return flipStatus(id, ConsumingParty.STATUS_ACTIVE, AuditAction.CONSUMING_PARTY_ACTIVATED);
  }

  @Override
  @Transactional
  public ConsumingPartyView allowSchema(UUID partyId, UUID schemaId) {
    ConsumingParty party = require(partyId);
    if (schemas.findById(schemaId).isEmpty()) {
      throw new NotFoundException(ErrorCode.KH_CNS_1404, "consumer.allowlist-schema-not-found");
    }
    consumingParties.insertAllowedSchema(partyId, schemaId);
    audit.record(
        AuditAction.CONSUMING_PARTY_SCHEMA_ALLOWED,
        "consuming_party",
        party.getCode(),
        Map.of("schemaId", schemaId.toString()));
    return toView(party);
  }

  @Override
  @Transactional
  public boolean disallowSchema(UUID partyId, UUID schemaId) {
    Optional<ConsumingParty> party = findInTenant(partyId);
    if (party.isEmpty()) {
      return false; // idempotent: nothing to remove for an unknown party
    }
    int removed = consumingParties.deleteAllowedSchema(partyId, schemaId);
    if (removed > 0) {
      audit.record(
          AuditAction.CONSUMING_PARTY_SCHEMA_DISALLOWED,
          "consuming_party",
          party.get().getCode(),
          Map.of("schemaId", schemaId.toString()));
    }
    return removed > 0;
  }

  private ConsumingPartyView flipStatus(UUID id, String status, AuditAction action) {
    ConsumingParty party = require(id);
    if (!status.equals(party.getStatus())) {
      party.setStatus(status);
      consumingParties.save(party);
      audit.record(action, "consuming_party", party.getCode(), null);
    }
    return toView(party);
  }

  /** Fetch a party that must exist in the current tenant, or throw {@code KH-CNS-0404}. */
  private ConsumingParty require(UUID id) {
    return findInTenant(id)
        .orElseThrow(
            () -> new NotFoundException(ErrorCode.KH_CNS_0404, "consumer.party-not-found"));
  }

  private Optional<ConsumingParty> findInTenant(UUID id) {
    UUID tenantId = TenantContext.current();
    return consumingParties.findById(id).filter(party -> tenantId.equals(party.getTenantId()));
  }

  private ConsumingPartyView toView(ConsumingParty party) {
    List<ConsumingPartyView.AllowedSchema> allowed =
        consumingParties.findAllowedSchemaIds(party.getId()).stream()
            .map(
                schemaId ->
                    new ConsumingPartyView.AllowedSchema(
                        schemaId, schemas.findById(schemaId).map(ref -> ref.code()).orElse(null)))
            .toList();
    return new ConsumingPartyView(
        party.getId(),
        party.getCode(),
        party.getNameI18n(),
        party.getStatus(),
        party.getCreatedAt(),
        allowed);
  }
}
