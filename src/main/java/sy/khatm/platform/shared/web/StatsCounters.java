package sy.khatm.platform.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Pilot-metrics counters (KH-1.1.3, spec FS-1.5.3) — a plain {@code GROUP BY action} aggregation
 * over {@code audit_log} for the request's window, never a separate bookkeeping table. Counters
 * only, no PII, no claim content (P1).
 *
 * @param issued credentials issued ({@code CREDENTIAL_ISSUED} — includes both single-issue and
 *     every row of a bulk batch)
 * @param revoked credentials revoked ({@code CREDENTIAL_REVOKED})
 * @param consumed credential uses consumed ({@code CREDENTIAL_CONSUMED})
 * @param consumeDenied consume attempts denied by the schema allowlist ({@code
 *     CONSUME_SCHEMA_DENIED}, KH-1.4.3)
 * @param claimsRedeemed wallet claim-code redemptions ({@code CLAIM_CODE_REDEEMED})
 * @param verifyOk online verifications that resolved valid ({@code CREDENTIAL_VERIFY_OK})
 * @param verifyFailed online verifications that resolved invalid ({@code CREDENTIAL_VERIFY_FAILED})
 */
@Schema(name = "StatsCounters", description = "Pilot-metrics counters for the requested window")
public record StatsCounters(
    long issued,
    long revoked,
    long consumed,
    long consumeDenied,
    long claimsRedeemed,
    long verifyOk,
    long verifyFailed) {}
