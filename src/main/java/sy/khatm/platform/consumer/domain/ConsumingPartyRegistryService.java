package sy.khatm.platform.consumer.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.consumer.api.ConsumingPartyRef;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.consumer.persistence.ConsumingPartyRepository;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;

/**
 * Default {@link ConsumingPartyRegistry} implementation.
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

  @Override
  @Transactional
  public ConsumingPartyRef ensure(String code) {
    UUID tenantId = TenantContext.current();
    // Deterministic (not Uuidv7 — this id must be reproducible from the same (tenant, code) pair
    // every call, which a time-ordered id can never be) so the same caller-supplied code always
    // resolves to the same row: find-or-create by id, rather than by a lookup column. KH-0.2.1's
    // original stand-in used a hash of `code` stored in `api_key_hash`; that column is gone as of
    // V2__auth_api_keys.sql (spec FS-0.6b D3 — real API-key authentication for a consuming party
    // now lives in rbac's `api_key` table instead), so this is purely an internal id derivation,
    // unrelated to authentication.
    UUID id = UUID.nameUUIDFromBytes((tenantId + ":" + code).getBytes(StandardCharsets.UTF_8));
    Optional<ConsumingParty> existing = consumingParties.findById(id);
    if (existing.isPresent()) {
      return new ConsumingPartyRef(id, code);
    }

    ConsumingParty party = new ConsumingParty();
    party.setId(id);
    party.setTenantId(tenantId);
    party.setNameI18n(new LocalizedText(code, code));
    party.setStatus("ACTIVE");
    party.setCreatedAt(Instant.now());
    consumingParties.save(party);
    return new ConsumingPartyRef(party.getId(), code);
  }
}
