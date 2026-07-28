package sy.khatm.platform.credential.api;

import java.time.Instant;
import sy.khatm.platform.shared.LocalizedText;

/**
 * One row of {@code GET /api/v1/credentials}'s search/list result (KH-1.1.4).
 *
 * <p>Proof/status metadata only — no claims, no {@code sdJwt}, no holder pseudoRef (P1 rule; a list
 * of up to 100 rows is exactly the wrong place to carry per-row signed-payload strings). Callers
 * needing a single credential's full detail (including the JWT) already have {@code GET
 * /api/v1/credentials/{id}} for that.
 *
 * @param id internal UUID (opaque)
 * @param ref human-readable reference
 * @param schemaCode credential type code
 * @param schemaName the schema's bilingual display name
 * @param issuedAt when this credential was issued
 * @param validTo expiry timestamp
 * @param maxUses maximum allowed consumptions
 * @param usesRemaining remaining consumption count
 * @param revoked whether this credential has been revoked
 * @param status explicit lifecycle status (spec FS-1.6 D1/D5): one of {@code ACTIVE}, {@code
 *     EXHAUSTED}, {@code REVOKED}, {@code SUSPENDED}, {@code EXPIRED} — derived, not stored; see
 *     {@code credential.domain.CredentialStatus}'s Javadoc for precedence and for why {@code
 *     SUSPENDED} is not reachable yet
 * @param usesConsumed {@code maxUses - usesRemaining} (spec FS-1.6 D5), so a caller can render "X
 *     of Y" without doing the subtraction itself
 */
public record CredentialSummary(
    String id,
    String ref,
    String schemaCode,
    LocalizedText schemaName,
    Instant issuedAt,
    Instant validTo,
    int maxUses,
    int usesRemaining,
    boolean revoked,
    String status,
    int usesConsumed) {}
