package sy.khatm.platform.consumer.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.consumer.api.ConsumingPartyRef;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.consumer.persistence.ConsumingPartyRepository;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;

/**
 * Default {@link ConsumingPartyRegistry} implementation — the runtime consume-path SPI (resolve a
 * party by code, schema-scoping checks, active-status check). Admin-plane management lives in
 * {@code ConsumingPartyAdminService}.
 *
 * <p>This class is module-private. External code must depend on {@link ConsumingPartyRegistry}, not
 * this class.
 */
@Service
class ConsumingPartyRegistryService implements ConsumingPartyRegistry {

  private final ConsumingPartyRepository consumingParties;

  ConsumingPartyRegistryService(ConsumingPartyRepository consumingParties) {
    this.consumingParties = consumingParties;
  }

  /**
   * Find-or-create by deterministic id (see {@link ConsumingPartyIds}).
   *
   * <p><b>Deliberately not {@code @Transactional}</b> (KH-1.4.4 D6): the find-or-create is
   * inherently racy — two callers can both miss the initial {@code findById} for a brand-new code
   * and both attempt the {@code INSERT}. By running each repository call in its own transaction (no
   * enclosing one), a lost race surfaces as a {@link DataIntegrityViolationException} from {@code
   * saveAndFlush} whose transaction rolls back cleanly on its own — so the follow-up re-read runs
   * on a clean connection and returns the winner's row, rather than hitting an aborted transaction
   * the way it would inside one big {@code @Transactional} boundary. The entity forces a true
   * {@code INSERT} (it is {@link org.springframework.data.domain.Persistable}), so the conflict is
   * a genuine primary-key violation, never a silent merge/update of the winner's row. The one
   * caller that reaches this at runtime, {@code CredentialService#consume}, is itself deliberately
   * non-transactional for the same family of reason.
   */
  @Override
  public ConsumingPartyRef ensure(String code) {
    UUID tenantId = TenantContext.current();
    UUID id = ConsumingPartyIds.deterministicId(tenantId, code);
    Optional<ConsumingParty> existing = consumingParties.findById(id);
    if (existing.isPresent()) {
      return new ConsumingPartyRef(id, code);
    }

    ConsumingParty party = new ConsumingParty();
    party.setId(id);
    party.setTenantId(tenantId);
    party.setCode(code);
    party.setNameI18n(new LocalizedText(code, code));
    party.setStatus(ConsumingParty.STATUS_ACTIVE);
    party.setCreatedAt(Instant.now());
    try {
      consumingParties.saveAndFlush(party);
      return new ConsumingPartyRef(id, code);
    } catch (DataIntegrityViolationException raced) {
      // Lost the find-or-create race: a concurrent caller committed the row under this exact
      // deterministic id. Re-read it (fresh transaction — this method holds none) and return the
      // winner's row directly.
      return consumingParties
          .findById(id)
          .map(winner -> new ConsumingPartyRef(winner.getId(), code))
          .orElseThrow(() -> raced);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isSchemaAllowed(UUID partyId, UUID schemaId) {
    return consumingParties.existsAllowedSchema(partyId, schemaId);
  }

  @Override
  @Transactional
  public void allowSchema(UUID partyId, UUID schemaId) {
    consumingParties.insertAllowedSchema(partyId, schemaId);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isActive(UUID partyId) {
    return consumingParties
        .findById(partyId)
        .map(party -> ConsumingParty.STATUS_ACTIVE.equals(party.getStatus()))
        .orElse(false);
  }
}
