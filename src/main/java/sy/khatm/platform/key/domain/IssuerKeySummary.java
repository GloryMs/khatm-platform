package sy.khatm.platform.key.domain;

import java.time.Instant;

/**
 * Summary of an {@link IssuerKey} row, returned by {@link KeyLifecycleService#bootstrapIfNeeded}
 * and {@link KeyLifecycleService#rotate} for logging/test assertions without exposing the JPA
 * entity itself outside the domain layer.
 *
 * @param kid the key id
 * @param state the key's lifecycle state ({@code PENDING}/{@code ACTIVE}/{@code RETIRING}/{@code
 *     RETIRED})
 * @param validFrom when this key became valid
 */
record IssuerKeySummary(String kid, String state, Instant validFrom) {}
