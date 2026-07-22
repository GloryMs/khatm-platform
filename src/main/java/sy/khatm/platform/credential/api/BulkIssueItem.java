package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * One row of a {@link BulkIssueRequest} (KH-1.1.3) — issued via the same {@code
 * sy.khatm.platform.credential.domain.CredentialService#issue} path a single {@code POST /issue}
 * call uses, so every guard that path enforces (schema-published, signing) applies identically per
 * row.
 *
 * @param claims claim name/value pairs to disclose selectively — identical meaning to {@link
 *     IssueRequest#claims}
 * @param pseudoRef pseudonymous holder identifier for this row; {@code null} falls back to {@code
 *     CredentialService#issue}'s own default, the same behavior a single-issue call with no {@code
 *     holderRef} would get
 * @param validMinutes overrides {@link BulkIssueRequest#defaults}' {@code validMinutes} for this
 *     row only; {@code null} uses the batch default
 * @param maxUses overrides {@link BulkIssueRequest#defaults}' {@code maxUses} for this row only;
 *     {@code null} uses the batch default
 */
@Schema(name = "BulkIssueItem", description = "One credential to issue within a bulk batch")
public record BulkIssueItem(
    Map<String, Object> claims, String pseudoRef, Integer validMinutes, Integer maxUses) {}
