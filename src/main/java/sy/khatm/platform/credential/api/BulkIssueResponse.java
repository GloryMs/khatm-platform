package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Full report of a {@code POST /api/v1/credentials/bulk} call (KH-1.1.3) — every item's outcome,
 * whether it succeeded or failed; the batch never rolls back as a whole (spec brief D2).
 *
 * @param total number of items in the request
 * @param succeeded number of items that issued successfully
 * @param failed number of items that failed
 * @param results index-aligned outcomes, one per request item
 */
@Schema(name = "BulkIssueResponse", description = "Full per-item report of a bulk issuance batch")
public record BulkIssueResponse(
    int total, int succeeded, int failed, List<BulkIssueItemResult> results) {}
