package sy.khatm.platform.key.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One signing key's lifecycle status within a {@link SigningKeysResponse} (spec FS-1.5.4 #4,
 * KH-1.1.5-BE).
 *
 * @param kid the key id
 * @param state {@code PENDING}/{@code ACTIVE}/{@code RETIRING}/{@code RETIRED}
 * @param validFrom when this key became valid
 * @param validTo when this key stopped (or will stop) being valid; {@code null} if never retired
 */
@Schema(name = "SigningKeyView", description = "A signing key's lifecycle status, no JWK material")
public record SigningKeyView(String kid, String state, Instant validFrom, Instant validTo) {}
