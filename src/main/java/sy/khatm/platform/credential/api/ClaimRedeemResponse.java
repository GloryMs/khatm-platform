package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Result of successfully redeeming a claim code (spec FS-1.2.1 D4) — the full, one-time delivery of
 * a credential to a wallet. The platform never stores this shape anywhere; by the time this
 * response is built, {@code claim_code.disclosures_enc} has already been zeroed in the same
 * transaction (D2).
 *
 * <p>The wallet is contractually obligated (spec FS-1.2.1 D4) to persist this response immediately,
 * before any display — there is no way to ask the platform for it again.
 *
 * @param ref the credential's human-readable reference (e.g. {@code CRE-2026-482917})
 * @param credential the compact SD-JWT (digests only, no disclosed values) — the same string stored
 *     in {@code credential.signed_payload}
 * @param disclosures every disclosure, base64url-encoded, in the exact form an SD-JWT presentation
 *     tilde-joins them in (empty if the credential was issued with zero claims)
 * @param schema the credential's schema, display shape
 * @param statusListUri the credential's status-list reference (spec FS-0.4 D3 shape; a placeholder
 *     value — the raw status list id, not yet a resolvable URL — until KH-1.3 publishes the real
 *     signed bitstring artifact endpoint)
 * @param issuedAt when the underlying credential was issued (not when this claim code was redeemed)
 */
@Schema(
    name = "ClaimRedeemResponse",
    description = "Full, one-time delivery of a credential to a wallet (spec FS-1.2.1 D4)")
public record ClaimRedeemResponse(
    String ref,
    String credential,
    List<String> disclosures,
    ClaimSchemaRef schema,
    String statusListUri,
    Instant issuedAt) {}
