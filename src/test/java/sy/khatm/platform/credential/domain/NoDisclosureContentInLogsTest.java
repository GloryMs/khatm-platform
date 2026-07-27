package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.authlete.sd.SDJWT;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.persistence.ClaimCodeRepository;
import sy.khatm.platform.credential.worker.ClaimCodeExpiryWorker;
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.4 §6 DoD #7 — no disclosure value and no salt ever appears in a log line (SEC §9). Captures
 * every log event across a full issue → verify → claim-code cycle and asserts none of the known
 * plaintext claim value or the disclosures' salts appear anywhere in the captured messages.
 */
class NoDisclosureContentInLogsTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private ClaimRedemptionService redemptionService;
  @Autowired private ClaimCodeRepository claimCodes;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;
  @Autowired private AuditService auditService;
  @Autowired private SystemAccessExecutor systemAccess;

  private Logger rootLogger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    appender = new ListAppender<>();
    appender.start();
    rootLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    rootLogger.detachAppender(appender);
  }

  @Test
  void issueVerifyAndClaimCodeCycle_neverLogsDisclosureValuesOrSalts() throws Exception {
    Map<String, Object> claims = Map.of("secretValue", "TOP-SECRET-VALUE-XYZ-987");

    IssueResponse issued =
        credentialService.issue(
            new IssueRequest("LogProbe/v1", "holder-log-probe", 1, 60, claims, List.of()));
    credentialService.verify(issued.sdJwt());
    credentialService.issueClaimCode(
        UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));

    List<String> saltsUsed =
        SDJWT.parse(issued.sdJwt()).getDisclosures().stream()
            .map(d -> d.getSalt())
            .collect(Collectors.toList());
    assertThat(saltsUsed).isNotEmpty();

    List<String> messages =
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());

    assertThat(messages).noneMatch(m -> m.contains("TOP-SECRET-VALUE-XYZ-987"));
    for (String salt : saltsUsed) {
      assertThat(messages).noneMatch(m -> m.contains(salt));
    }
  }

  /**
   * Extends the no-disclosure guarantee to the claim_code expiry sweep (task constraint): across an
   * issue → claim-code → force-expire → sweep cycle, the sweep's own log output (the count, the
   * audit row) must never contain a plaintext claim value or a disclosure salt. The sweep logs only
   * a count by construction; this pins that property so a future change cannot leak it.
   */
  @Test
  @Transactional
  void expirySweep_neverLogsDisclosureValuesOrSalts() {
    Map<String, Object> claims = Map.of("secretValue", "TOP-SECRET-SWEEP-999");
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest("SweepLogProbe/v1", "holder-sweep-log", 1, 60, claims, List.of()));
    credentialService.issueClaimCode(
        UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));
    List<String> saltsUsed =
        SDJWT.parse(issued.sdJwt()).getDisclosures().stream()
            .map(d -> d.getSalt())
            .collect(Collectors.toList());
    assertThat(saltsUsed).isNotEmpty();

    // Force the just-created claim code into the expired window, then run the sweep (constructed
    // directly — its bean is worker-role-gated, absent in this test-profile context). Flush first
    // so
    // the JPA-saved claim_code row is visible to the raw JDBC UPDATE below.
    entityManager.flush();
    jdbc.update(
        "UPDATE claim_code SET expires_at = now() - interval '1 hour' WHERE credential_id = ?",
        UUID.fromString(issued.id()));
    int zeroed = new ClaimCodeExpiryWorker(claimCodes, auditService, systemAccess).sweep();
    assertThat(zeroed)
        .as("the sweep must have zeroed the expired code for this assertion to mean anything")
        .isEqualTo(1);

    List<String> messages =
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    assertThat(messages).noneMatch(m -> m.contains("TOP-SECRET-SWEEP-999"));
    for (String salt : saltsUsed) {
      assertThat(messages).noneMatch(m -> m.contains(salt));
    }
  }

  /**
   * Spec FS-1.2.1 DoD #6 — extends the no-disclosure guarantee to the claim-redeem path: an issue →
   * claim-code → redeem cycle's log output must never contain a plaintext claim value, a disclosure
   * salt, or the raw claim code itself (SEC §9.7 — the code is a one-time secret, access logs
   * capture paths and headers, never bodies, but this asserts the application's own log lines carry
   * none of it either).
   */
  @Test
  void redeemCycle_neverLogsDisclosureValuesSaltsOrTheClaimCodeItself() {
    Map<String, Object> claims = Map.of("secretValue", "TOP-SECRET-REDEEM-VALUE-321");

    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "RedeemLogProbe/v1", "holder-redeem-log-probe", 1, 60, claims, List.of()));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));

    List<String> saltsUsed =
        SDJWT.parse(issued.sdJwt()).getDisclosures().stream()
            .map(d -> d.getSalt())
            .collect(Collectors.toList());
    assertThat(saltsUsed).isNotEmpty();

    ClaimRedeemResult result = redemptionService.redeem(claimCode.code());
    assertThat(result.disclosures()).isNotEmpty();

    List<String> messages =
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    assertThat(messages).noneMatch(m -> m.contains("TOP-SECRET-REDEEM-VALUE-321"));
    assertThat(messages).noneMatch(m -> m.contains(claimCode.code()));
    for (String salt : saltsUsed) {
      assertThat(messages).noneMatch(m -> m.contains(salt));
    }
  }

  /**
   * KH-1.2.2 — extends the no-disclosure guarantee to the claim-code mint path: an issue → mint →
   * mint-again cycle (the second mint voids the first code) must never log a plaintext claim value,
   * a disclosure salt, or either raw claim code.
   */
  @Test
  void mintCycle_neverLogsDisclosureValuesSaltsOrEitherClaimCode() {
    Map<String, Object> claims = Map.of("secretValue", "TOP-SECRET-MINT-VALUE-654");

    IssueResponse issued =
        credentialService.issue(
            new IssueRequest("MintLogProbe/v1", "holder-mint-log-probe", 1, 60, claims, List.of()));

    List<String> saltsUsed =
        SDJWT.parse(issued.sdJwt()).getDisclosures().stream()
            .map(d -> d.getSalt())
            .collect(Collectors.toList());
    assertThat(saltsUsed).isNotEmpty();

    ClaimCodeIssued first =
        credentialService.mintClaimCode(UUID.fromString(issued.id()), issued.sdJwt(), null);
    ClaimCodeIssued second =
        credentialService.mintClaimCode(UUID.fromString(issued.id()), issued.sdJwt(), null);

    List<String> messages =
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    assertThat(messages).noneMatch(m -> m.contains("TOP-SECRET-MINT-VALUE-654"));
    assertThat(messages).noneMatch(m -> m.contains(first.code()));
    assertThat(messages).noneMatch(m -> m.contains(second.code()));
    for (String salt : saltsUsed) {
      assertThat(messages).noneMatch(m -> m.contains(salt));
    }
  }
}
