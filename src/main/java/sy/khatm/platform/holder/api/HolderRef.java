package sy.khatm.platform.holder.api;

import java.util.UUID;

/**
 * Opaque reference to a registered holder — the only holder data other modules may see.
 *
 * @param id internal UUID, used as the {@code credential.holder_id} foreign key
 * @param pseudoRef the pseudonymous reference supplied by the issuing organisation
 */
public record HolderRef(UUID id, String pseudoRef) {}
