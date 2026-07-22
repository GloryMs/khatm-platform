package sy.khatm.platform.consumer.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Derives a consuming party's row id deterministically from {@code (tenantId, code)}.
 *
 * <p>Not a time-ordered {@code Uuidv7} — the id must be reproducible from the same {@code (tenant,
 * code)} pair on every call, so that {@code ConsumingPartyRegistryService#ensure} (implicit
 * find-or-create) and {@code ConsumingPartyAdminService#create} (explicit admin registration) can
 * never diverge into two rows for one code (KH-1.4.4 D2). Both derive the id here so there is a
 * single source of truth for the derivation.
 */
final class ConsumingPartyIds {

  private ConsumingPartyIds() {}

  static UUID deterministicId(UUID tenantId, String code) {
    return UUID.nameUUIDFromBytes((tenantId + ":" + code).getBytes(StandardCharsets.UTF_8));
  }
}
