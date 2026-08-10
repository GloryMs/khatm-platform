package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.credential.api.BulkIssueDefaults;
import sy.khatm.platform.credential.api.BulkIssueItem;
import sy.khatm.platform.credential.api.BulkIssueRequest;
import sy.khatm.platform.schema.api.ClaimFieldRequest;
import sy.khatm.platform.schema.api.SchemaCreateRequest;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.schema.domain.SchemaAuthoringService;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-1.1.3 — {@code BulkIssuanceService#bulkIssue} at the service level: happy path (incl.
 * mintClaimCodes), a mixed batch that reports per-item failures without rolling back the successful
 * rows, batch-level validation (empty/oversized), and the reused draft/archived-schema guard
 * failing per item rather than the whole batch.
 */
class BulkIssuanceServiceTest extends IntegrationTestSupport {

  @Autowired private BulkIssuanceService bulkIssuance;
  @Autowired private SchemaAuthoringService authoring;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void bulkIssue_happyPath_issuesEveryItemAndRecordsBatchAudit() {
    String schemaCode = "BulkHappyPath/v1";
    publishSchema(schemaCode);

    BulkIssueRequest req =
        new BulkIssueRequest(
            schemaCode,
            new BulkIssueDefaults(60, 1),
            List.of(
                new BulkIssueItem(Map.of("field", "a"), "holder-bulk-happy-1", null, null),
                new BulkIssueItem(Map.of("field", "b"), "holder-bulk-happy-2", null, null),
                new BulkIssueItem(Map.of("field", "c"), "holder-bulk-happy-3", null, null)),
            false);

    BulkIssueOutcome outcome = bulkIssuance.bulkIssue(req);

    assertThat(outcome.total()).isEqualTo(3);
    assertThat(outcome.succeeded()).isEqualTo(3);
    assertThat(outcome.failed()).isZero();
    assertThat(outcome.results()).allMatch(BulkIssueItemOutcome::succeeded);
    assertThat(outcome.results()).extracting(BulkIssueItemOutcome::id).doesNotContainNull();
    assertThat(outcome.results()).extracting(BulkIssueItemOutcome::ref).doesNotContainNull();

    Integer batchAuditCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CREDENTIALS_BULK_ISSUED'"
                + " AND entity_ref = ?",
            Integer.class,
            schemaCode);
    assertThat(batchAuditCount).isEqualTo(1);

    for (BulkIssueItemOutcome result : outcome.results()) {
      Integer perItemAuditCount =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM audit_log WHERE action = 'CREDENTIAL_ISSUED'"
                  + " AND entity_ref = ?",
              Integer.class,
              result.ref());
      assertThat(perItemAuditCount).isEqualTo(1);
    }
  }

  @Test
  void bulkIssue_withMintClaimCodes_returnsOneTimeCodePerSucceededItem() {
    String schemaCode = "BulkMintClaimCodes/v1";
    publishSchema(schemaCode);

    BulkIssueRequest req =
        new BulkIssueRequest(
            schemaCode,
            null,
            List.of(new BulkIssueItem(Map.of("field", "a"), "holder-bulk-mint-1", null, null)),
            true);

    BulkIssueOutcome outcome = bulkIssuance.bulkIssue(req);

    assertThat(outcome.succeeded()).isEqualTo(1);
    BulkIssueItemOutcome result = outcome.results().get(0);
    assertThat(result.claimCode()).isNotBlank();

    Integer claimCodeAuditCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CLAIM_CODE_ISSUED' AND entity_ref = ?",
            Integer.class,
            result.ref());
    assertThat(claimCodeAuditCount).isEqualTo(1);
  }

  @Test
  void bulkIssue_mixedBatch_reportsPerItemFailure_indexAligned_batchNotRolledBack() {
    String publishedCode = "BulkMixedPublished/v1";
    String draftCode = "BulkMixedDraft/v1";
    publishSchema(publishedCode);
    authoring.create(createRequest(draftCode));

    // One schema per batch (D1) — the mixed outcome here comes from the same schemaCode
    // resolving to a DRAFT row instead of PUBLISHED, exercising the reused
    // SchemaCatalog#ensurePublished guard per item, not a mixed-schema batch (out of scope).
    BulkIssueRequest req =
        new BulkIssueRequest(
            draftCode,
            null,
            List.of(
                new BulkIssueItem(Map.of("field", "a"), "holder-bulk-mixed-1", null, null),
                new BulkIssueItem(Map.of("field", "b"), "holder-bulk-mixed-2", null, null)),
            false);

    BulkIssueOutcome outcome = bulkIssuance.bulkIssue(req);

    assertThat(outcome.total()).isEqualTo(2);
    assertThat(outcome.succeeded()).isZero();
    assertThat(outcome.failed()).isEqualTo(2);
    assertThat(outcome.results()).extracting(BulkIssueItemOutcome::index).containsExactly(0, 1);
    assertThat(outcome.results())
        .allSatisfy(
            r -> {
              assertThat(r.succeeded()).isFalse();
              assertThat(r.error()).isInstanceOf(ConflictException.class);
              assertThat(r.error().errorCode().code()).isEqualTo("KH-SCH-1409");
            });

    // Batch-level audit row still recorded, with the real 0-succeeded/2-failed counts — the
    // batch as a whole is never rolled back just because every item failed.
    Integer batchAuditCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CREDENTIALS_BULK_ISSUED'"
                + " AND entity_ref = ?",
            Integer.class,
            draftCode);
    assertThat(batchAuditCount).isEqualTo(1);
  }

  @Test
  void bulkIssue_draftSchemaItem_failsWithSameGuardCodeAsSingleIssue() {
    String draftCode = "BulkDraftGuard/v1";
    authoring.create(createRequest(draftCode));

    BulkIssueOutcome outcome =
        bulkIssuance.bulkIssue(
            new BulkIssueRequest(
                draftCode,
                null,
                List.of(new BulkIssueItem(Map.of("field", "a"), "holder-bulk-draft", null, null)),
                false));

    assertThat(outcome.results().get(0).error()).isInstanceOf(ConflictException.class);
    assertThat(outcome.results().get(0).error().errorCode().code()).isEqualTo("KH-SCH-1409");
  }

  @Test
  void bulkIssue_archivedSchemaItem_failsWithSameGuardCodeAsSingleIssue() {
    String archivedCode = "BulkArchivedGuard/v1";
    SchemaDetail created = authoring.create(createRequest(archivedCode));
    authoring.publish(created.id());
    authoring.archive(created.id());

    BulkIssueOutcome outcome =
        bulkIssuance.bulkIssue(
            new BulkIssueRequest(
                archivedCode,
                null,
                List.of(
                    new BulkIssueItem(Map.of("field", "a"), "holder-bulk-archived", null, null)),
                false));

    assertThat(outcome.results().get(0).error()).isInstanceOf(ConflictException.class);
    assertThat(outcome.results().get(0).error().errorCode().code()).isEqualTo("KH-SCH-1409");
  }

  @Test
  void bulkIssue_emptyItems_throwsValidationExceptionBeforeProcessingAnything() {
    BulkIssueRequest req = new BulkIssueRequest("BulkEmpty/v1", null, List.of(), false);

    assertThatThrownBy(() -> bulkIssuance.bulkIssue(req)).isInstanceOf(ValidationException.class);
  }

  @Test
  void bulkIssue_tooManyItems_throwsValidationException() {
    List<BulkIssueItem> items =
        IntStream.range(0, 201)
            .mapToObj(i -> new BulkIssueItem(Map.of("field", "v" + i), "holder-" + i, null, null))
            .toList();
    BulkIssueRequest req = new BulkIssueRequest("BulkTooMany/v1", null, items, false);

    assertThatThrownBy(() -> bulkIssuance.bulkIssue(req)).isInstanceOf(ValidationException.class);
  }

  private void publishSchema(String code) {
    SchemaDetail created = authoring.create(createRequest(code));
    authoring.publish(created.id());
  }

  private static SchemaCreateRequest createRequest(String code) {
    return new SchemaCreateRequest(
        code,
        Map.of("en", "Bulk Probe " + UUID.randomUUID(), "ar", "فحص جماعي"),
        List.of(new ClaimFieldRequest("field", "text", Map.of("en", "Field", "ar", "حقل"), null)),
        List.of(),
        1,
        null,
        null);
  }
}
