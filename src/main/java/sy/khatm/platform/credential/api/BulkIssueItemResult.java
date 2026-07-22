package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row's outcome within a {@link BulkIssueResponse} — index-aligned to the request's {@link
 * BulkIssueRequest#items}, so a caller can always map a result back to the row that produced it.
 *
 * @param index zero-based position of this row in the original request
 * @param status {@code ISSUED} or {@code FAILED}
 * @param id the issued credential's internal id; {@code null} when {@code status} is {@code FAILED}
 * @param ref the issued credential's human-readable ref; {@code null} when {@code status} is {@code
 *     FAILED}
 * @param claimCode a one-time wallet claim code, present only when the request set {@code
 *     mintClaimCodes: true} and this row succeeded — shown here exactly once, same one-time
 *     contract as {@code POST /{id}/claim-code}
 * @param error why this row failed; {@code null} when {@code status} is {@code ISSUED}
 */
@Schema(name = "BulkIssueItemResult", description = "One bulk-issue row's outcome")
public record BulkIssueItemResult(
    int index, String status, String id, String ref, String claimCode, BulkIssueItemError error) {}
