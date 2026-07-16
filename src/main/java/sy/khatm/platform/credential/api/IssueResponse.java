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
 */
@Schema(name = "IssueResponse", description = "Result of a successful SD-JWT credential issuance")
public record IssueResponse(String id, String ref, String sdJwt) {}
