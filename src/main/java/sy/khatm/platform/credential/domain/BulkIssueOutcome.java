package sy.khatm.platform.credential.domain;

import java.util.List;

/**
 * Full report of a {@link BulkIssuanceService#bulkIssue} call — the domain-layer counterpart to
 * {@link sy.khatm.platform.credential.api.BulkIssueResponse}. Public for the same reason as {@link
 * BulkIssueItemOutcome}.
 *
 * @param total number of items in the request
 * @param succeeded number of items that issued successfully
 * @param failed number of items that failed
 * @param results index-aligned outcomes, one per request item
 */
public record BulkIssueOutcome(
    int total, int succeeded, int failed, List<BulkIssueItemOutcome> results) {}
