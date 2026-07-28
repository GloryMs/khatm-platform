package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Result of a {@code POST /api/v1/credentials/holder-status} lookup (spec FS-1.6 D3).
 *
 * <p>Proof/status metadata only — never claim content (P1 rule), matching every other credential
 * lookup surface.
 *
 * @param status explicit lifecycle status: one of {@code ACTIVE}, {@code EXHAUSTED}, {@code
 *     REVOKED}, {@code SUSPENDED}, {@code EXPIRED} — see {@link CredentialSummary#status}
 * @param maxUses maximum allowed consumptions
 * @param usesRemaining remaining consumption count
 * @param lastConsumedAt when this credential's most recent consumption happened, or {@code null} if
 *     it has never been consumed
 */
@Schema(name = "HolderStatusResponse", description = "A credential's current lifecycle status")
public record HolderStatusResponse(
    String status, int maxUses, int usesRemaining, Instant lastConsumedAt) {}
