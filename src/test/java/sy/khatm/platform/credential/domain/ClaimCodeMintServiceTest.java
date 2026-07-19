package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-1.2.2 — {@code CredentialService#mintClaimCode} at the service level: spec FS-1.2.1 D2's
 * re-issue recovery path exposed for an issuer, the "one live code per credential" voiding
 * behavior, and the 404/409 rejection paths.
 */
class ClaimCodeMintServiceTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void mint_validCredential_deliversCodeAndAudits() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "MintHappy/v1", "holder-mint-happy", 1, 60, Map.of("field", "value"), List.of()));

    ClaimCodeIssued minted =
        credentialService.mintClaimCode(UUID.fromString(issued.id()), issued.sdJwt(), null);

    assertThat(minted.code()).isNotBlank();
    assertThat(minted.expiresAt()).isAfter(Instant.now());

    Boolean disclosuresPresent =
        jdbc.queryForObject(
            "SELECT disclosures_enc IS NOT NULL FROM claim_code WHERE credential_id = ?",
            Boolean.class,
            UUID.fromString(issued.id()));
    assertThat(disclosuresPresent).isTrue();

    Integer auditCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CLAIM_CODE_ISSUED'"
                + " AND entity_ref = ?",
            Integer.class,
            issued.ref());
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void mint_customTtl_isHonored() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "MintTtl/v1", "holder-mint-ttl", 1, 60, Map.of("field", "value"), List.of()));

    Instant before = Instant.now();
    ClaimCodeIssued minted =
        credentialService.mintClaimCode(UUID.fromString(issued.id()), issued.sdJwt(), 2);

    assertThat(minted.expiresAt()).isBefore(before.plus(Duration.ofMinutes(3)));
    assertThat(minted.expiresAt()).isAfter(before.plus(Duration.ofSeconds(90)));
  }

  @Test
  void mint_secondTime_voidsThePriorPendingCode() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "MintVoidPrior/v1",
                "holder-mint-void-prior",
                1,
                60,
                Map.of("field", "value"),
                List.of()));

    ClaimCodeIssued first =
        credentialService.mintClaimCode(UUID.fromString(issued.id()), issued.sdJwt(), null);
    ClaimCodeIssued second =
        credentialService.mintClaimCode(UUID.fromString(issued.id()), issued.sdJwt(), null);

    assertThat(second.code()).isNotEqualTo(first.code());

    Integer liveCodeCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM claim_code WHERE credential_id = ? AND disclosures_enc"
                + " IS NOT NULL AND claimed_at IS NULL",
            Integer.class,
            UUID.fromString(issued.id()));
    assertThat(liveCodeCount)
        .as("only the second mint's code should still have disclosures_enc populated")
        .isEqualTo(1);

    Integer totalRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM claim_code WHERE credential_id = ?",
            Integer.class,
            UUID.fromString(issued.id()));
    assertThat(totalRows).as("both rows still exist, just the first is voided").isEqualTo(2);
  }

  @Test
  void mint_unknownCredentialId_throwsNotFound() {
    assertThatThrownBy(() -> credentialService.mintClaimCode(UUID.randomUUID(), "irrelevant", null))
        .isInstanceOf(NotFoundException.class)
        .satisfies(
            ex -> assertThat(((NotFoundException) ex).errorCode().code()).isEqualTo("KH-CRD-0404"));
  }

  @Test
  void mint_revokedCredential_throwsConflict() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "MintRevoked/v1",
                "holder-mint-revoked",
                1,
                60,
                Map.of("field", "value"),
                List.of()));
    credentialService.revoke(UUID.fromString(issued.id()));

    assertThatThrownBy(
            () ->
                credentialService.mintClaimCode(UUID.fromString(issued.id()), issued.sdJwt(), null))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            ex -> assertThat(((ConflictException) ex).errorCode().code()).isEqualTo("KH-CRD-0409"));
  }

  @Test
  void mint_expiredCredential_throwsConflict() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "MintExpired/v1",
                "holder-mint-expired",
                1,
                60,
                Map.of("field", "value"),
                List.of()));
    // valid_to > valid_from is a DB CHECK constraint — push both into the past together.
    jdbc.update(
        "UPDATE credential SET valid_from = ?, valid_to = ? WHERE id = ?",
        Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)),
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        UUID.fromString(issued.id()));

    assertThatThrownBy(
            () ->
                credentialService.mintClaimCode(UUID.fromString(issued.id()), issued.sdJwt(), null))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            ex -> assertThat(((ConflictException) ex).errorCode().code()).isEqualTo("KH-CRD-0409"));
  }
}
