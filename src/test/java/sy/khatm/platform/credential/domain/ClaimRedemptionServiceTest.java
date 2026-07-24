package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.persistence.ClaimCodeRepository;
import sy.khatm.platform.credential.worker.ClaimCodeExpiryWorker;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-1.2.1 DoD 1-3: the redeem service-level contract — happy path (D4 delivery shape plus the
 * on-claim zeroing), the generic 404 for a second redeem, and for an expired/zeroed code (D5).
 */
class ClaimRedemptionServiceTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private ClaimRedemptionService redemptionService;
  @Autowired private ClaimCodeRepository claimCodes;
  @Autowired private AuditService auditService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  @Test
  void redeem_validCode_deliversCredentialAndZeroesDisclosuresAndAudits() {
    Map<String, Object> claims = Map.of("result", "NO_RECORD", "caseNumber", "CN-001");
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "RedeemHappy/v1", "holder-redeem-happy", 1, 60, claims, List.of("caseNumber")));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));

    ClaimRedeemResult result = redemptionService.redeem(claimCode.code());

    assertThat(result.ref()).isEqualTo(issued.ref());
    assertThat(result.credential()).isEqualTo(extractCompactJwt(issued.sdJwt()));
    assertThat(result.disclosures()).hasSize(2);
    assertThat(result.schemaVersion()).isEqualTo(1);
    assertThat(result.schemaNameI18n()).isNotNull();
    assertThat(result.statusListUri()).isNotBlank();
    assertThat(result.issuedAt()).isNotNull();

    UUID credentialId = UUID.fromString(issued.id());
    Integer maxUses =
        jdbc.queryForObject(
            "SELECT max_uses FROM credential WHERE id = ?", Integer.class, credentialId);
    Instant validTo =
        jdbc.queryForObject(
                "SELECT valid_to FROM credential WHERE id = ?", Timestamp.class, credentialId)
            .toInstant();
    assertThat(result.maxUses()).isEqualTo(maxUses);
    assertThat(result.expiresAt()).isEqualTo(validTo);

    Boolean claimedAtSet =
        jdbc.queryForObject(
            "SELECT claimed_at IS NOT NULL FROM claim_code WHERE credential_id = ?",
            Boolean.class,
            credentialId);
    Boolean disclosuresNull =
        jdbc.queryForObject(
            "SELECT disclosures_enc IS NULL FROM claim_code WHERE credential_id = ?",
            Boolean.class,
            credentialId);
    assertThat(claimedAtSet).isTrue();
    assertThat(disclosuresNull).isTrue();

    Integer auditCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CLAIM_CODE_REDEEMED'"
                + " AND entity_ref = ?",
            Integer.class,
            issued.ref());
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void redeem_credentialWithZeroClaims_deliversEmptyDisclosureList() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest("RedeemEmpty/v1", "holder-redeem-empty", 1, 60, Map.of(), List.of()));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));

    ClaimRedeemResult result = redemptionService.redeem(claimCode.code());

    assertThat(result.disclosures()).isEmpty();
  }

  @Test
  void redeem_sameCodeTwice_secondCallFailsWithGeneric404() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "RedeemTwice/v1", "holder-redeem-twice", 1, 60, Map.of("result", "X"), List.of()));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));

    redemptionService.redeem(claimCode.code());

    assertThatThrownBy(() -> redemptionService.redeem(claimCode.code()))
        .isInstanceOf(NotFoundException.class)
        .satisfies(
            ex -> assertThat(((NotFoundException) ex).errorCode().code()).isEqualTo("KH-CLM-0404"));
  }

  @Test
  void redeem_unknownCode_failsWithGeneric404() {
    assertThatThrownBy(() -> redemptionService.redeem("never-issued-code"))
        .isInstanceOf(NotFoundException.class)
        .satisfies(
            ex -> assertThat(((NotFoundException) ex).errorCode().code()).isEqualTo("KH-CLM-0404"));
  }

  // @Transactional: this test deliberately leaves an expired-but-still-populated row (redeem()
  // correctly rejects without zeroing it), which — in this shared-context suite's one persistent
  // database — would otherwise sit around and inflate a later, unrelated sweep-count assertion
  // (ClaimCodeExpirySweepTest et al.) the first time anything sweeps it. The rollback this
  // annotation triggers erases the row when the test ends, same rationale as
  // ClaimCodeExpirySweepTest's own tests.
  @Test
  @Transactional
  void redeem_expiredCode_failsWithGeneric404_sameAsUnknown() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "RedeemExpired/v1",
                "holder-redeem-expired",
                1,
                60,
                Map.of("result", "X"),
                List.of()));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));
    // Flush first so the JPA-saved claim_code row is visible to the raw JDBC UPDATE below (this
    // single @Transactional test never otherwise commits between the two calls, unlike the
    // non-transactional happy-path tests above). Clear afterward so the entity Hibernate just
    // cached with the ORIGINAL (future) expires_at doesn't shadow the JDBC-written value the next
    // time something queries this row by identity.
    entityManager.flush();
    jdbc.update(
        "UPDATE claim_code SET expires_at = ? WHERE credential_id = ?",
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        UUID.fromString(issued.id()));
    entityManager.clear();

    assertThatThrownBy(() -> redemptionService.redeem(claimCode.code()))
        .isInstanceOf(NotFoundException.class)
        .satisfies(
            ex -> assertThat(((NotFoundException) ex).errorCode().code()).isEqualTo("KH-CLM-0404"));
  }

  // @Transactional: worker.sweep() is called on a manually-`new`'d (not Spring-managed)
  // ClaimCodeExpiryWorker, so its own @Transactional annotation gets no AOP interception — it
  // relies on an ambient transaction, exactly like ClaimCodeExpirySweepTest's own tests do.
  @Test
  @Transactional
  void redeem_expirySweepZeroedCode_failsWithGeneric404() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "RedeemZeroed/v1",
                "holder-redeem-zeroed",
                1,
                60,
                Map.of("result", "X"),
                List.of()));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));
    // Same flush/clear reasoning as the sibling test above.
    entityManager.flush();
    jdbc.update(
        "UPDATE claim_code SET expires_at = ? WHERE credential_id = ?",
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        UUID.fromString(issued.id()));
    entityManager.clear();
    int zeroed = new ClaimCodeExpiryWorker(claimCodes, auditService).sweep();
    assertThat(zeroed).isEqualTo(1);

    assertThatThrownBy(() -> redemptionService.redeem(claimCode.code()))
        .isInstanceOf(NotFoundException.class)
        .satisfies(
            ex -> assertThat(((NotFoundException) ex).errorCode().code()).isEqualTo("KH-CLM-0404"));
  }

  private static String extractCompactJwt(String sdJwtPresentation) {
    int tilde = sdJwtPresentation.indexOf('~');
    return tilde < 0 ? sdJwtPresentation : sdJwtPresentation.substring(0, tilde);
  }
}
