package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The failure reason for one {@link BulkIssueItemResult} — the same {@code code}/localized {@code
 * message} shape a single-request error envelope carries, just scoped to one batch row instead of
 * the whole HTTP response.
 *
 * @param code the registry {@code ErrorCode} string (e.g. {@code KH-SCH-1409} for a
 *     draft/archived-schema rejection)
 * @param message the localized, human-readable message for the request's locale
 */
@Schema(name = "BulkIssueItemError", description = "Why one bulk-issue item failed")
public record BulkIssueItemError(String code, String message) {}
