package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of a successful credential issuance.
 *
 * <p>{@code sdJwt} is the full SD-JWT presentation the issuer hands to the holder at issuance time
 * — compact JWT plus every disclosure, tilde-separated ({@code <jwt>~<d1>~..~<dn>~}, spec FS-0.4
 * D6). This is a transient, one-time delivery — the platform never persists it in this form; only
 * the digest-only compact JWT is stored ({@code credential.signed_payload}).
 *
 * @param id internal UUID of the credential row (opaque to callers)
 * @param ref human-readable reference (e.g. {@code CRE-2026-482917}); stable across re-issues
 * @param sdJwt the full SD-JWT presentation: {@code
 *     <compact-jwt>~<disclosure_1>~..~<disclosure_n>~}
 * @param claimed whether the holder's wallet has already claimed this credential (spec FS-2.7a
 *     §3.1). Always {@code false} on a fresh issuance; the replay semantics that make it meaningful
 *     arrive with KH-2.8.2.
 * @param issuerClientId the machine issuer client that issued this credential (KH-2.8.1); {@code
 *     null} when a console session issued it
 */
@Schema(name = "IssueResponse", description = "Result of a successful SD-JWT credential issuance")
public record IssueResponse(
    String id, String ref, String sdJwt, boolean claimed, String issuerClientId) {

  /** A console-issued, not-yet-claimed credential — the shape every pre-KH-2.8.1 caller means. */
  public IssueResponse(String id, String ref, String sdJwt) {
    this(id, ref, sdJwt, false, null);
  }
}
