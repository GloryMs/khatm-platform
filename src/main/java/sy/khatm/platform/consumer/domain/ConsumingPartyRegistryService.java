package sy.khatm.platform.consumer.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import sy.khatm.platform.shared.Uuidv7;

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
    byte[] hash = sha256(code);
    Optional<ConsumingParty> existing =
        consumingParties.findByTenantIdAndApiKeyHash(tenantId, hash);
    if (existing.isPresent()) {
      return new ConsumingPartyRef(existing.get().getId(), code);
    }

    ConsumingParty party = new ConsumingParty();
    party.setId(Uuidv7.generate());
    party.setTenantId(tenantId);
    party.setNameI18n(new LocalizedText(code, code));
    party.setApiKeyHash(hash);
    party.setStatus("ACTIVE");
    party.setCreatedAt(Instant.now());
    consumingParties.save(party);
    return new ConsumingPartyRef(party.getId(), code);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm", e);
    }
  }
}
