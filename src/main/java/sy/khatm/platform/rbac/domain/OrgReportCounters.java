package sy.khatm.platform.rbac.domain;

/**
 * Proofs-not-content counters for one tenant within a report window (KH-2.6b, spec FS-2.5 §4) — the
 * four categories the spec names by name: issue, verify (split ok/failed, mirroring {@code
 * shared.web.StatsCounters}'s established shape), consume, revoke. Numbers only — never a row, a
 * claim, or any other content (P1).
 *
 * @param issued credentials issued ({@code CREDENTIAL_ISSUED})
 * @param verifyOk online verifications that resolved valid ({@code CREDENTIAL_VERIFY_OK})
 * @param verifyFailed online verifications that resolved invalid ({@code CREDENTIAL_VERIFY_FAILED})
 * @param consumed credential uses consumed ({@code CREDENTIAL_CONSUMED})
 * @param revoked credentials revoked ({@code CREDENTIAL_REVOKED})
 */
public record OrgReportCounters(
    long issued, long verifyOk, long verifyFailed, long consumed, long revoked) {

  static final OrgReportCounters ZERO = new OrgReportCounters(0, 0, 0, 0, 0);

  OrgReportCounters plus(OrgReportCounters other) {
    return new OrgReportCounters(
        issued + other.issued,
        verifyOk + other.verifyOk,
        verifyFailed + other.verifyFailed,
        consumed + other.consumed,
        revoked + other.revoked);
  }
}
