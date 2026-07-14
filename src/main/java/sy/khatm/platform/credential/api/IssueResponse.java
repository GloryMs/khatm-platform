package sy.khatm.platform.credential.api;

/**
 * Result of a successful credential issuance.
 *
 * @param id internal UUID of the credential row (opaque to callers)
 * @param ref human-readable reference (e.g. {@code CRE-2026-482917}); stable across re-issues
 * @param jwt compact JWS string to embed in QR codes or NFC payloads
 */
public record IssueResponse(String id, String ref, String jwt) {}
