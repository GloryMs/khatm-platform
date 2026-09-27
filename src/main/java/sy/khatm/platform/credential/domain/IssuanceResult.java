package sy.khatm.platform.credential.domain;

import sy.khatm.platform.credential.api.IssueResponse;

/**
 * A single issuance's response plus whether it was answered from an {@code Idempotency-Key} record
 * (KH-2.8.2, spec FS-2.7a D7) — the web layer turns {@code replayed} into the {@code
 * Idempotent-Replayed: true} response header.
 *
 * @param response the response body
 * @param replayed {@code true} when nothing new was issued
 */
public record IssuanceResult(IssueResponse response, boolean replayed) {}
