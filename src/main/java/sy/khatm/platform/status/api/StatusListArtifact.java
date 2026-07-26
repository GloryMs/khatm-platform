package sy.khatm.platform.status.api;

/**
 * A status list's signed bitstring artifact, as served publicly at {@code GET
 * /sl/{tenantSlug}/{listCode}} (spec FS-1.3 D2, FS-2.1 D8).
 *
 * @param signedArtifact the compact JWS itself (see {@code status.domain.StatusListPublisher} for
 *     its claim shape)
 * @param version the list's version this artifact was signed from — the response {@code ETag}
 */
public record StatusListArtifact(String signedArtifact, long version) {}
