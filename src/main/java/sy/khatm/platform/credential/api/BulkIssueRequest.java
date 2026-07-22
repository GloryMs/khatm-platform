package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Request to issue a batch of credentials against one schema (KH-1.1.3) — the C3 console wizard's
 * CSV-per-schema flow. Every item is issued independently via {@code
 * sy.khatm.platform.credential.domain.CredentialService#issue} (no parallel issuance logic, no
 * bypass of any single-issue guard); one bad row never rolls back the batch (spec brief D2).
 *
 * <p>Capped at 200 items — a synchronous, size-capped batch is the V1 answer (ADR-09 async/queued
 * bulk stays out of scope); an empty or oversized batch is rejected wholesale as {@code
 * KH-CRD-0400} before any item is processed.
 *
 * @param schemaCode credential type code every item in this batch is issued against — one schema
 *     per batch, matching the C3 wizard's CSV-per-schema flow
 * @param defaults batch-wide {@code validMinutes}/{@code maxUses}, overridable per item; {@code
 *     null} uses {@code CredentialService#issue}'s own defaults
 * @param items the rows to issue, 1 to 200 of them (enforced by {@code
 *     sy.khatm.platform.credential.domain.BulkIssuanceService}, not Bean Validation, so an empty or
 *     oversized batch reports the specific {@code KH-CRD-0400} code rather than the generic
 *     validation-failure one); index-aligned to {@link BulkIssueResponse#results}
 * @param mintClaimCodes when {@code true}, a one-time wallet claim code is minted for every
 *     successfully issued item (the existing {@code CredentialService#mintClaimCode} path) and
 *     returned in that item's result — the only time it is ever shown, same one-time contract as
 *     {@code POST /{id}/claim-code}
 */
@Schema(
    name = "BulkIssueRequest",
    description = "Request to issue a batch of credentials against one schema")
public record BulkIssueRequest(
    @NotBlank String schemaCode,
    BulkIssueDefaults defaults,
    List<BulkIssueItem> items,
    Boolean mintClaimCodes) {}
