package sy.khatm.platform.credential.domain;

import sy.khatm.platform.shared.error.KhatmException;

/**
 * One row's outcome from {@link BulkIssuanceService#bulkIssue} — the domain-layer counterpart to
 * {@link sy.khatm.platform.credential.api.BulkIssueItemResult}, carrying the raw {@link
 * KhatmException} for a failed row rather than an already-localized message; {@code
 * credential.web.CredentialController} resolves {@code error.messageKey()} against the request's
 * locale, the same division of labor {@code VerifyResponse.reasonMessage} already uses.
 *
 * <p>Public so that {@code credential.web.CredentialController} (a different sub-package of the
 * same module) can map it to the API shape; Modulith — not Java visibility — keeps it out of reach
 * of other modules, since it lives outside the {@code api} named interface (mirrors {@link
 * ClaimCodeIssued}'s rationale).
 *
 * @param index zero-based position of this row in the original request
 * @param id the issued credential's internal id; {@code null} on failure
 * @param ref the issued credential's human-readable ref; {@code null} on failure
 * @param claimCode a one-time wallet claim code, present only when the request set {@code
 *     mintClaimCodes: true} and this row succeeded
 * @param error why this row failed; {@code null} on success
 */
public record BulkIssueItemOutcome(
    int index, String id, String ref, String claimCode, KhatmException error) {

  /**
   * @return {@code true} if this row issued successfully
   */
  public boolean succeeded() {
    return error == null;
  }
}
