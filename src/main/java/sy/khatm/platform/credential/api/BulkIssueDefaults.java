package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Batch-wide defaults for a {@link BulkIssueRequest} (KH-1.1.3), overridable per {@link
 * BulkIssueItem}.
 *
 * @param validMinutes validity window in minutes from issuance, applied to every item that does not
 *     specify its own; {@code null} uses {@code CredentialService#issue}'s own default (60)
 * @param maxUses maximum number of times each item may be consumed, applied to every item that does
 *     not specify its own; {@code null} uses {@code CredentialService#issue}'s own default (1)
 */
@Schema(name = "BulkIssueDefaults", description = "Batch-wide defaults, overridable per item")
public record BulkIssueDefaults(Integer validMinutes, Integer maxUses) {}
