package sy.khatm.platform.consumer.api;

import java.util.UUID;

/**
 * Opaque reference to a registered consuming party — the only data other modules may see.
 *
 * @param id internal UUID, used as the {@code consumption_event.consuming_party_id} foreign key
 * @param code the caller-supplied party code this reference was resolved from
 */
public record ConsumingPartyRef(UUID id, String code) {}
