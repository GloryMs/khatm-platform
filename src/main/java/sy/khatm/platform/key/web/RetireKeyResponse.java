package sy.khatm.platform.key.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Result of {@code POST /api/v1/admin/signing-keys/{kid}/retire} (spec FS-2.3 D4, KH-2.3a).
 *
 * @param kid the now-{@code RETIRED} key's id
 * @param state always {@code RETIRED}
 * @param validTo when this key stopped being {@code ACTIVE} (i.e. when it became {@code RETIRING})
 */
@Schema(name = "RetireKeyResponse", description = "The now-RETIRED signing key")
public record RetireKeyResponse(String kid, String state, Instant validTo) {}
