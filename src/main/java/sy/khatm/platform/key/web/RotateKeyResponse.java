package sy.khatm.platform.key.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Result of {@code POST /api/v1/admin/signing-keys/rotate} (spec FS-2.3 D2, KH-2.3a; {@code
 * provider}, D5/D6, KH-2.3b).
 *
 * @param kid the newly created, now-{@code ACTIVE} key's id
 * @param state always {@code ACTIVE}
 * @param provider the provider the new key was actually created on ({@code SOFT}/{@code VAULT}) —
 *     confirms which backend a migration rotation landed on
 * @param validFrom when the new key became valid
 */
@Schema(name = "RotateKeyResponse", description = "The newly created, now-ACTIVE signing key")
public record RotateKeyResponse(String kid, String state, String provider, Instant validFrom) {}
