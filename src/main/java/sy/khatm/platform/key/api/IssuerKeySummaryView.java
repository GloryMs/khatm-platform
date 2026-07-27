package sy.khatm.platform.key.api;

import java.time.Instant;

/**
 * Cross-module view of a signing key's lifecycle summary (spec FS-2.1 D6) — never the JWK material
 * itself.
 *
 * @param kid the key's id
 * @param state {@code PENDING}, {@code ACTIVE}, {@code RETIRING}, or {@code RETIRED}
 * @param validFrom when this key became valid
 */
public record IssuerKeySummaryView(String kid, String state, Instant validFrom) {}
