package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Result of a credential issuance, fresh or replayed.
 *
 * <p>{@code sdJwt} is the full SD-JWT presentation the issuer hands to the holder at issuance time
 * — compact JWT plus every disclosure, tilde-separated ({@code <jwt>~<d1>~..~<dn>~}, spec FS-0.4
 * D6). This is a transient, one-time delivery — the platform never persists it in this form; only
 * the digest-only compact JWT is stored ({@code credential.signed_payload}). That is why an
 * idempotent replay (KH-2.8.2, response header {@code Idempotent-Replayed: true}) always has {@code
 * sdJwt = null}: in claim-code mode it reissues the claim code instead, in direct mode it reports
 * {@code deliveryLost = true} and the connector decides (revoke and reissue under a new key).
 *
 * @param id internal UUID of the credential row (opaque to callers)
 * @param ref human-readable reference (e.g. {@code CRE-2026-482917}); stable across re-issues
 * @param sdJwt the full SD-JWT presentation: {@code
 *     <compact-jwt>~<disclosure_1>~..~<disclosure_n>~}; {@code null} on a replay
 * @param claimed whether the holder's wallet has already claimed this credential (spec FS-2.7a
 *     §3.1) — {@code false} on a fresh issuance, the real state on a replay
 * @param issuerClientId the machine issuer client that issued this credential (KH-2.8.1); {@code
 *     null} when a console session issued it
 * @param holderRef the holder reference the credential was issued to — the one sent, or the random
 *     one the platform generated for a human session that sent none (KH-2.8.2, veto V1-b)
 * @param claimCode a one-time wallet claim code: minted with the credential when the request set
 *     {@code mintClaimCode: true}, or reissued by a replay while still unclaimed; otherwise {@code
 *     null}. Shown here once, never stored in clear
 * @param claimCodeExpiresAt when {@code claimCode} stops working; {@code null} with it
 * @param claimCodeReissued {@code true} when a replay replaced the previous, undelivered claim code
 *     (the old one no longer works)
 * @param deliveryLost {@code true} when a replay has nothing left to deliver: a direct-mode {@code
 *     sdJwt} (never stored), or a claim code whose pending disclosures were already zeroed
 */
@Schema(name = "IssueResponse", description = "Result of a successful SD-JWT credential issuance")
public record IssueResponse(
    String id,
    String ref,
    String sdJwt,
    boolean claimed,
    String issuerClientId,
    String holderRef,
    @Schema(nullable = true) String claimCode,
    @Schema(nullable = true) Instant claimCodeExpiresAt,
    boolean claimCodeReissued,
    boolean deliveryLost) {

  /** A console-issued, not-yet-claimed credential — the shape every pre-KH-2.8.1 caller means. */
  public IssueResponse(String id, String ref, String sdJwt) {
    this(id, ref, sdJwt, false, null, null, null, null, false, false);
  }
}
