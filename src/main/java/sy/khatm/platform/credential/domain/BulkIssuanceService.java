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
import sy.khatm.platform.rbac.api.CurrentActor;
import sy.khatm.platform.rbac.api.CurrentActorResolver;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.IntegrityException;
import sy.khatm.platform.shared.error.KhatmException;
import sy.khatm.platform.shared.error.ValidationException;

/**
 * Bulk credential issuance (KH-1.1.3, {@code POST /api/v1/credentials/bulk}) — the C3 console
 * wizard's CSV-per-schema flow. Deliberately narrow: synchronous with a hard size cap is the V1
 * answer (ADR-09 async/queued bulk stays out of scope), and every item is issued through {@link
 * CredentialService#issueBatchRow} — the single-issue path minus the per-request idempotency key
 * (the batch's key covers its rows): no parallel issuance logic, no bypass of any single-issue
 * guard (schema-published, signing, connector contract).
 *
 * <p><b>Per-item independence (spec brief D2):</b> this class is a separate bean from {@link
 * CredentialService} specifically so each loop iteration's call to {@link
 * CredentialService#issueBatchRow} goes through Spring's real transactional proxy — a self-invoked
 * {@code @Transactional} method would not (the same reason {@code AtomicConsumptionRecorder} exists
 * as its own bean). {@link #bulkIssue} itself carries no {@code @Transactional} annotation, so each
 * item's issuance (and, when requested, its claim-code mint) commits or rolls back entirely on its
 * own: one bad row can never take the rest of the batch down with it.
 *
 * <p><b>Claim codes (spec brief D3, KH-2.8.2):</b> when {@code mintClaimCodes} is {@code true},
 * each row is issued with {@link IssueRequest#mintClaimCode} set, so its one-time wallet claim code
 * is minted inside that row's own issuing transaction — a row is either issued with its code or not
 * issued at all (before KH-2.8.2 the mint was a second transaction, and a failed mint left an
 * issued credential reported as {@code FAILED}).
 *
 * <p><b>Idempotency (KH-2.8.2, spec FS-2.7a D5–D7, veto V4):</b> one {@code Idempotency-Key} per
 * batch, mandatory for machine issuer clients. Because the rows commit independently, the key's
 * {@code IN_PROGRESS} claim commits before the first row and turns {@code DONE} with a code-free
 * result snapshot after the last one; a twin arriving in between gets {@code 409 KH-IDEM-0409}. A
 * replay returns the same {@code results[]} and reissues the claim code of every issued,
 * still-unclaimed row. If an unexpected error stops the loop, the rows already processed are kept
 * in the snapshot and the unprocessed ones are recorded as {@code KH-SYS-0500} failures, so a retry
 * replays the truth instead of issuing twice. A process crash mid-batch leaves the claim {@code
 * IN_PROGRESS} (409) until retention expires — the connector checks its rows with {@code GET
 * /credentials/{id}} and continues under a new key.
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
  private final IssuanceIdempotencyGuard idempotency;
  private final CurrentActorResolver currentActorResolver;

  public BulkIssuanceService(
      CredentialService credentialService,
      AuditService audit,
      SchemaCatalog schemas,
      IssuanceIdempotencyGuard idempotency,
      CurrentActorResolver currentActorResolver) {
    this.credentialService = credentialService;
    this.audit = audit;
    this.schemas = schemas;
    this.idempotency = idempotency;
    this.currentActorResolver = currentActorResolver;
  }

  /** Same as {@link #bulkIssue(BulkIssueRequest, IssuanceContext)} with no request headers. */
  public BulkIssueOutcome bulkIssue(BulkIssueRequest req) {
    return bulkIssue(req, IssuanceContext.NONE);
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
   *     item is processed; {@link ErrorCode#KH_IDEM_0400} for a machine issuer client without an
   *     {@code Idempotency-Key}, or a malformed key
   * @throws sy.khatm.platform.shared.error.ConflictException {@link ErrorCode#KH_IDEM_0409} while
   *     the same key's batch is still running; {@link ErrorCode#KH_IDEM_0422} for the same key with
   *     a different body
   */
  public BulkIssueOutcome bulkIssue(BulkIssueRequest req, IssuanceContext context) {
    UUID issuerClientId =
        currentActorResolver
            .resolve()
            .filter(a -> a.kind() == CurrentActor.ActorKind.API_KEY_ISSUER_CLIENT)
            .map(CurrentActor::id)
            .orElse(null);
    String key = idempotency.effectiveKey(context, issuerClientId != null);

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
    IssuanceIdempotencyGuard.Claim claim = null;
    if (key != null) {
      // Commits on its own (this method has no transaction): the claim must be visible before the
      // first row commits, so a concurrent twin sees IN_PROGRESS rather than issuing again.
      claim =
          idempotency.claim(
              IssuanceIdempotency.Scope.BULK, key, idempotency.requestHash(req), issuerClientId);
      if (claim.isReplay()) {
        return idempotency.replayBulk(claim.existing(), mintClaimCodes, key, req.schemaCode());
      }
    }

    Integer defaultValidMinutes = req.defaults() == null ? null : req.defaults().validMinutes();
    Integer defaultMaxUses = req.defaults() == null ? null : req.defaults().maxUses();

    List<BulkIssueItemOutcome> results = new ArrayList<>(items.size());
    int succeeded = 0;
    int failed = 0;
    try {
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
                  null,
                  null,
                  mintClaimCodes);
          // A separate-bean call — the real Spring proxy, its own fresh transaction (see class
          // Javadoc). Never called as a self-invocation of this class's own method.
          IssueResponse issued = credentialService.issueBatchRow(itemRequest);
          results.add(
              new BulkIssueItemOutcome(
                  index,
                  issued.id(),
                  issued.ref(),
                  issued.claimCode(),
                  issued.holderRef(),
                  false,
                  null));
          succeeded++;
        } catch (KhatmException e) {
          results.add(new BulkIssueItemOutcome(index, null, null, null, null, false, e));
          failed++;
        }
      }
    } catch (RuntimeException unexpected) {
      if (claim != null) {
        recordInterruptedBatch(claim.rowId(), results, items.size());
      }
      throw unexpected;
    }

    audit.record(
        AuditAction.CREDENTIALS_BULK_ISSUED,
        "credential",
        req.schemaCode(),
        Map.of("total", items.size(), "succeeded", succeeded, "failed", failed));

    if (claim != null) {
      idempotency.completeBulk(claim.rowId(), results);
    }
    return new BulkIssueOutcome(items.size(), succeeded, failed, results);
  }

  /**
   * Keeps an interrupted batch replayable: processed rows as they happened, every unprocessed row
   * as a {@code KH-SYS-0500} failure (it was genuinely not issued).
   */
  private void recordInterruptedBatch(
      UUID claimRowId, List<BulkIssueItemOutcome> processed, int total) {
    List<BulkIssueItemOutcome> snapshot = new ArrayList<>(processed);
    for (int index = processed.size(); index < total; index++) {
      snapshot.add(
          new BulkIssueItemOutcome(
              index,
              null,
              null,
              null,
              null,
              false,
              new IntegrityException(ErrorCode.KH_SYS_0500, ErrorCode.KH_SYS_0500.messageKey())));
    }
    idempotency.completeBulk(claimRowId, snapshot);
  }
}
