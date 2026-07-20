package sy.khatm.platform.credential.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Result of {@link ClaimRedemptionService#redeem}, built entirely from the in-memory material
 * decrypted inside that method's transaction — never re-read from the database afterward, since
 * {@code disclosures_enc} is already {@code NULL} there by the time this record exists (spec
 * FS-1.2.1 D2).
 *
 * <p>Public so that {@code credential.web.ClaimController} (a different sub-package in the same
 * module) can map it to the wire-shape {@link
 * sy.khatm.platform.credential.api.ClaimRedeemResponse}; Modulith — not Java visibility — keeps it
 * out of reach of other modules, mirroring {@link ClaimCodeIssued}'s existing rationale.
 *
 * @param ref the credential's human-readable reference
 * @param credential the compact SD-JWT (digests only)
 * @param disclosures every disclosure, base64url-encoded, in presentation order
 * @param schemaId the credential's schema id
 * @param schemaNameI18n the schema's bilingual display name
 * @param schemaVersion the schema version
 * @param statusListUri the credential's fully-qualified public status-list URL (spec FS-1.3 D7 —
 *     the real {@code GET /sl/{tenantSlug}/{listCode}} value, replacing the pre-KH-1.3 placeholder)
 * @param issuedAt when the underlying credential was issued
 */
public record ClaimRedeemResult(
    String ref,
    String credential,
    List<String> disclosures,
    UUID schemaId,
    LocalizedText schemaNameI18n,
    int schemaVersion,
    String statusListUri,
    Instant issuedAt) {}
