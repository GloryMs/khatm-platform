package sy.khatm.platform.credential.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import sy.khatm.platform.credential.api.BulkIssueItem;
import sy.khatm.platform.credential.api.BulkIssueRequest;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.KhatmException;
import sy.khatm.platform.shared.error.ValidationException;

/**
 * Bulk credential issuance (KH-1.1.3, {@code POST /api/v1/credentials/bulk}) — the C3 console
 * wizard's CSV-per-schema flow. Deliberately narrow: synchronous with a hard size cap is the V1
 * answer (ADR-09 async/queued bulk stays out of scope), and every item is issued through {@link
 * CredentialService#issue} unchanged — no parallel issuance logic, no bypass of any single-issue
 * guard (schema-published, signing).
 *
 * <p><b>Per-item independence (spec brief D2):</b> this class is a separate bean from {@link
 * CredentialService} specifically so each loop iteration's call to {@link CredentialService#issue}
 * goes through Spring's real transactional proxy — a self-invoked {@code @Transactional} method
 * would not (the same reason {@code AtomicConsumptionRecorder} exists as its own bean). {@link
 * #bulkIssue} itself carries no {@code @Transactional} annotation, so each item's issuance (and,
 * when requested, its claim-code mint) commits or rolls back entirely on its own: one bad row can
 * never take the rest of the batch down with it.
 *
 * <p><b>Claim codes (spec brief D3):</b> when {@code mintClaimCodes} is {@code true}, a
 * successfully issued item's one-time wallet claim code is minted via the existing {@link
 * CredentialService#mintClaimCode} path (voiding semantics untouched) — no new minting mechanism.
 * If the mint call itself fails (the credential row already committed, the mint's own separate
 * transaction did not), the row is still reported {@code FAILED} in the response; the underlying
 * credential exists but was never handed a claim code, recoverable only via {@code GET
 * /api/v1/credentials} search — an accepted edge case, not exercised by this batch's own
 * transaction boundary.
 *
 * <p>Module-private (Modulith-enforced, not Java visibility — mirrors {@link CredentialService}'s
 * rationale, since {@code credential.web.CredentialController} in a different sub-package of the
 * same module calls this directly).
 */
@Service
public class BulkIssuanceService {

  private static final int MAX_ITEMS = 200;

  private final CredentialService credentialService;
  private final AuditService audit;
  private final SchemaCatalog schemas;

  public BulkIssuanceService(
      CredentialService credentialService, AuditService audit, SchemaCatalog schemas) {
    this.credentialService = credentialService;
    this.audit = audit;
    this.schemas = schemas;
  }

  /**
   * Issue every item in {@code req}, independently, against {@code req.schemaCode()}.
   *
   * @param req the batch request
   * @return the full per-item report
   * @throws ValidationException {@link ErrorCode#KH_CRD_0400} if {@code items} is empty or exceeds
   *     {@value #MAX_ITEMS} entries — a batch-level rejection before any item is processed, never
   *     counted as a per-item failure; {@link ErrorCode#KH_ATT_0402} (KH-2.4, spec FS-2.4 item 2)
   *     if {@code req.schemaCode()} names a schema with {@code requires_attestation=true} —
   *     attested schemas are out of scope for bulk issuance entirely, rejected wholesale before any
   *     item is processed
   */
  public BulkIssueOutcome bulkIssue(BulkIssueRequest req) {
    List<BulkIssueItem> items = req.items() == null ? List.of() : req.items();
    if (items.isEmpty()) {
      throw new ValidationException(
          ErrorCode.KH_CRD_0400, "credential.bulk-validation-failed", "items must not be empty");
    }
    if (items.size() > MAX_ITEMS) {
      throw new ValidationException(
          ErrorCode.KH_CRD_0400,
          "credential.bulk-validation-failed",
          "items must not exceed " + MAX_ITEMS + " entries, got " + items.size());
    }
    if (schemas.findByCode(req.schemaCode()).map(SchemaRef::requiresAttestation).orElse(false)) {
      throw new ValidationException(ErrorCode.KH_ATT_0402, "attestation.bulk-not-supported");
    }

    boolean mintClaimCodes = Boolean.TRUE.equals(req.mintClaimCodes());
    Integer defaultValidMinutes = req.defaults() == null ? null : req.defaults().validMinutes();
    Integer defaultMaxUses = req.defaults() == null ? null : req.defaults().maxUses();

    List<BulkIssueItemOutcome> results = new ArrayList<>(items.size());
    int succeeded = 0;
    int failed = 0;
    for (int index = 0; index < items.size(); index++) {
      BulkIssueItem item = items.get(index);
      try {
        IssueRequest itemRequest =
            new IssueRequest(
                req.schemaCode(),
                item.pseudoRef(),
                item.maxUses() == null ? defaultMaxUses : item.maxUses(),
                item.validMinutes() == null ? defaultValidMinutes : item.validMinutes(),
                item.claims(),
                null,
                null);
        // A separate-bean call — the real Spring proxy, its own fresh transaction (see class
        // Javadoc). Never called as a self-invocation of this class's own method.
        IssueResponse issued = credentialService.issue(itemRequest);

        String claimCode = null;
        if (mintClaimCodes) {
          claimCode =
              credentialService
                  .mintClaimCode(UUID.fromString(issued.id()), issued.sdJwt(), null)
                  .code();
        }
        results.add(new BulkIssueItemOutcome(index, issued.id(), issued.ref(), claimCode, null));
        succeeded++;
      } catch (KhatmException e) {
        results.add(new BulkIssueItemOutcome(index, null, null, null, e));
        failed++;
      }
    }

    audit.record(
        AuditAction.CREDENTIALS_BULK_ISSUED,
        "credential",
        req.schemaCode(),
        Map.of("total", items.size(), "succeeded", succeeded, "failed", failed));

    return new BulkIssueOutcome(items.size(), succeeded, failed, results);
  }
}
