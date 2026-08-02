package sy.khatm.platform.key.domain;

import java.time.Instant;

/**
 * A signing key's lifecycle fields, no public JWK material (spec FS-1.5.4 #4, {@code GET
 * /api/v1/admin/signing-keys}) — consumed by {@code key/web/SigningKeyStatusController}.
 *
 * <p>Unlike {@link PublishedKey} (JWKS, {@code ACTIVE}/{@code RETIRING} only, carries the public
 * JWK), this is the full lifecycle view across every state including {@code RETIRED}, for an
 * operator dashboard rather than a verifier.
 *
 * @param kid the key id
 * @param state {@code PENDING}/{@code ACTIVE}/{@code RETIRING}/{@code RETIRED}
 * @param provider the {@link KeyProvider} backend this key's private material lives in ({@code
 *     SOFT}/{@code VAULT}, spec FS-2.3 D5/D6, C8's console panel is expected to show this per key)
 * @param validFrom when this key became valid
 * @param validTo when this key stopped (or will stop) being valid; {@code null} for a key that has
 *     never been retired
 */
public record IssuerKeyStatusView(
    String kid, String state, String provider, Instant validFrom, Instant validTo) {}
