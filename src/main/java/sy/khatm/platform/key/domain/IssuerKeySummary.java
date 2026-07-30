package sy.khatm.platform.key.domain;

import java.time.Instant;

/**
 * Summary of an {@link IssuerKey} row, returned by {@link KeyLifecycleService#bootstrapIfNeeded},
 * {@link KeyLifecycleService#rotate}, and {@link KeyLifecycleService#retire} — public (unlike most
 * of this package) so {@code key.web}'s controllers can map it to their own response records
 * without exposing the JPA entity itself outside the domain layer.
 *
 * @param kid the key id
 * @param state the key's lifecycle state ({@code PENDING}/{@code ACTIVE}/{@code RETIRING}/{@code
 *     RETIRED})
 * @param validFrom when this key became valid
 * @param validTo when this key stopped (or will stop) being valid; {@code null} for a key that has
 *     never been retired
 */
public record IssuerKeySummary(String kid, String state, Instant validFrom, Instant validTo) {}
