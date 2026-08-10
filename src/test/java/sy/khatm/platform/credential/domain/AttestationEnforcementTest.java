package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sy.khatm.platform.credential.api.AttestationRequest;
import sy.khatm.platform.credential.api.BulkIssueItem;
import sy.khatm.platform.credential.api.BulkIssueRequest;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.schema.api.ClaimFieldRequest;
import sy.khatm.platform.schema.api.SchemaCreateRequest;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.schema.domain.SchemaAuthoringService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-2.4-BE — attested-document support (spec FS-2.4 items 2/3): the deny-by-default attestation
 * enforcement in both directions, the {@code SCAN_ATTESTED} audit ordering/atomicity guarantee, the
 * bulk-issuance wholesale rejection, and {@code claims_def} {@code pattern} enforcement at
 * issuance.
 */
class AttestationEnforcementTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private BulkIssuanceService bulkIssuance;
  @Autowired private SchemaAuthoringService authoring;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  // ── Test 1: the four enforcement quadrants ────────────────────────────────

  @Test
  void issue_requiredSchema_withAttestation_succeeds() {
    String code = publishSchema("AttEnforceReqPresent/v1", true, List.of(field("name", null)));

    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                code,
                "holder-att-req-present",
                1,
                60,
                Map.of("name", "value"),
                List.of(),
                new AttestationRequest("scanned original")));

    assertThat(issued.id()).isNotBlank();
  }

  @Test
  void issue_requiredSchema_withoutAttestation_throwsAttestationRequired() {
    String code = publishSchema("AttEnforceReqAbsent/v1", true, List.of(field("name", null)));

    assertThatThrownBy(
            () ->
                credentialService.issue(
                    new IssueRequest(
                        code,
                        "holder-att-req-absent",
                        1,
                        60,
                        Map.of("name", "value"),
                        List.of(),
                        null)))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            e ->
                assertThat(((ValidationException) e).errorCode()).isEqualTo(ErrorCode.KH_ATT_0400));
  }

  @Test
  void issue_notRequiredSchema_withoutAttestation_succeeds() {
    String code = publishSchema("AttEnforceNotReqAbsent/v1", false, List.of(field("name", null)));

    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                code, "holder-att-notreq-absent", 1, 60, Map.of("name", "value"), List.of(), null));

    assertThat(issued.id()).isNotBlank();
  }

  @Test
  void issue_notRequiredSchema_withAttestation_throwsAttestationNotApplicable() {
    String code = publishSchema("AttEnforceNotReqPresent/v1", false, List.of(field("name", null)));

    assertThatThrownBy(
            () ->
                credentialService.issue(
                    new IssueRequest(
                        code,
                        "holder-att-notreq-present",
                        1,
                        60,
                        Map.of("name", "value"),
                        List.of(),
                        new AttestationRequest("uninvited note"))))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            e ->
                assertThat(((ValidationException) e).errorCode()).isEqualTo(ErrorCode.KH_ATT_0401));
  }

  // ── Test 2: SCAN_ATTESTED audit ordering + atomicity ──────────────────────

  @Test
  void issue_attestedSchema_writesScanAttestedBeforeCredentialIssued_sameTransaction() {
    String code = publishSchema("AttOrdering/v1", true, List.of(field("name", null)));

    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                code,
                "holder-att-ordering",
                1,
                60,
                Map.of("name", "value"),
                List.of(),
                new AttestationRequest("ordering probe note")));

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT action, id FROM audit_log WHERE entity_ref = ? AND action IN"
                + " ('SCAN_ATTESTED','CREDENTIAL_ISSUED') ORDER BY id ASC",
            issued.ref());

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("action")).isEqualTo("SCAN_ATTESTED");
    assertThat(rows.get(1).get("action")).isEqualTo("CREDENTIAL_ISSUED");

    Map<String, Object> detailRow =
        jdbc.queryForMap(
            "SELECT detail FROM audit_log WHERE entity_ref = ? AND action = 'SCAN_ATTESTED'",
            issued.ref());
    assertThat(detailRow.get("detail").toString()).contains("ordering probe note");
  }

  @Test
  void issue_attestedSchema_whenOuterTransactionRollsBack_leavesNoOrphanScanAttestedRow() {
    String code = publishSchema("AttRollback/v1", true, List.of(field("name", null)));
    String uniqueNote = "rollback probe note " + UUID.randomUUID();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    // issue() is plain @Transactional (REQUIRED) — called from inside this outer
    // TransactionTemplate's own transaction, it joins the same physical transaction rather than
    // opening a nested one, exactly the mechanism AuditServiceTransactionalTest proves generically
    // for AuditService#record alone. Marking the outer transaction rollback-only after a real,
    // successful issue() call proves the SCAN_ATTESTED row never survives independently of the
    // credential row and the CREDENTIAL_ISSUED row it was written alongside — no orphan is possible
    // because all three commit or roll back as one unit.
    transactionTemplate.executeWithoutResult(
        status -> {
          credentialService.issue(
              new IssueRequest(
                  code,
                  "holder-att-rollback",
                  1,
                  60,
                  Map.of("name", "value"),
                  List.of(),
                  new AttestationRequest(uniqueNote)));
          status.setRollbackOnly();
        });

    Integer scanAttestedCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE detail::text LIKE ?",
            Integer.class,
            "%" + uniqueNote + "%");
    assertThat(scanAttestedCount)
        .as("no orphan SCAN_ATTESTED row survives a rolled-back transaction")
        .isZero();
  }

  // ── Test 3: bulk rejection ─────────────────────────────────────────────────

  @Test
  void bulkIssue_attestedSchema_throwsBulkNotSupported() {
    String code = publishSchema("AttBulkReject/v1", true, List.of(field("name", null)));

    BulkIssueRequest req =
        new BulkIssueRequest(
            code,
            null,
            List.of(new BulkIssueItem(Map.of("name", "value"), "holder-att-bulk", null, null)),
            false);

    assertThatThrownBy(() -> bulkIssuance.bulkIssue(req))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            e ->
                assertThat(((ValidationException) e).errorCode()).isEqualTo(ErrorCode.KH_ATT_0402));
  }

  // ── Test 4: claims_def pattern enforcement ─────────────────────────────────

  @Test
  void issue_malformedPatternedClaim_wrongLength_throwsSchemaValidationFailed() {
    assertPatternRejected("AttPatternWrongLength/v1", "abc123");
  }

  @Test
  void issue_malformedPatternedClaim_uppercase_throwsSchemaValidationFailed() {
    assertPatternRejected(
        "AttPatternUppercase/v1",
        "0E096349C319F2E7560D15100F23541AC79ABF1A85ED46730C5E91966B7924A");
  }

  @Test
  void issue_malformedPatternedClaim_nonHex_throwsSchemaValidationFailed() {
    assertPatternRejected(
        "AttPatternNonHex/v1", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz");
  }

  @Test
  void issue_wellFormedPatternedClaim_succeeds() {
    String code =
        publishSchema("AttPatternValid/v1", false, List.of(field("doc_sha256", "^[0-9a-f]{64}$")));

    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                code,
                "holder-att-pattern-valid",
                1,
                60,
                Map.of(
                    "doc_sha256",
                    "0e096349c319f2e7560d15100f23541ac79abf1a85ed46730c5e91966b7924ae"),
                List.of(),
                null));

    assertThat(issued.id()).isNotBlank();
  }

  private void assertPatternRejected(String codeBase, String malformedValue) {
    String code = publishSchema(codeBase, false, List.of(field("doc_sha256", "^[0-9a-f]{64}$")));

    assertThatThrownBy(
            () ->
                credentialService.issue(
                    new IssueRequest(
                        code,
                        "holder-att-pattern-bad",
                        1,
                        60,
                        Map.of("doc_sha256", malformedValue),
                        List.of(),
                        null)))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            e ->
                assertThat(((ValidationException) e).errorCode()).isEqualTo(ErrorCode.KH_SCH_0400));
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private String publishSchema(
      String code, boolean requiresAttestation, List<ClaimFieldRequest> claimsDef) {
    SchemaDetail created =
        authoring.create(
            new SchemaCreateRequest(
                code,
                Map.of("en", "Attestation Probe", "ar", "فحص التصديق"),
                claimsDef,
                List.of(),
                1,
                null,
                requiresAttestation));
    authoring.publish(created.id());
    return code;
  }

  private static ClaimFieldRequest field(String name, String pattern) {
    return new ClaimFieldRequest(name, "text", Map.of("en", name, "ar", name), pattern);
  }
}
