package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.credential.api.ConsumeRequest;
import sy.khatm.platform.credential.api.HolderStatusResponse;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-1.6 D3 — {@code CredentialService#holderStatus}: proof-of-possession status lookup, the
 * deliberate, explicit reversal of PR #33's original "no live uses-remaining channel" stance.
 */
class HolderStatusTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;

  @Test
  void holderStatus_activeCredential_reportsActiveAndFullUses() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "HolderStatusActive/v1",
                "holder-status-active",
                2,
                60,
                Map.of("field", "value"),
                List.of(),
                null));

    HolderStatusResponse status = credentialService.holderStatus(bareJwt(issued));

    assertThat(status.status()).isEqualTo("ACTIVE");
    assertThat(status.maxUses()).isEqualTo(2);
    assertThat(status.usesRemaining()).isEqualTo(2);
    assertThat(status.lastConsumedAt()).isNull();
  }

  @Test
  void holderStatus_afterExhaustingConsumption_reportsExhaustedWithLastConsumedAt() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "HolderStatusExhausted/v1",
                "holder-status-exhausted",
                1,
                60,
                Map.of("field", "value"),
                List.of(),
                null));
    credentialService.consume(new ConsumeRequest(issued.id(), "consumer-a", null));

    HolderStatusResponse status = credentialService.holderStatus(bareJwt(issued));

    assertThat(status.status()).isEqualTo("EXHAUSTED");
    assertThat(status.usesRemaining()).isZero();
    assertThat(status.lastConsumedAt()).isNotNull();
  }

  @Test
  void holderStatus_revokedCredential_reportsRevokedNotExhausted() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "HolderStatusRevoked/v1",
                "holder-status-revoked",
                3,
                60,
                Map.of("field", "value"),
                List.of(),
                null));
    credentialService.revoke(UUID.fromString(issued.id()));

    HolderStatusResponse status = credentialService.holderStatus(bareJwt(issued));

    assertThat(status.status()).isEqualTo("REVOKED");
    assertThat(status.usesRemaining()).isEqualTo(3);
  }

  @Test
  void holderStatus_malformedJwt_throwsUnifiedNotFound() {
    assertThatThrownBy(() -> credentialService.holderStatus("not-a-real-jwt"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void holderStatus_tamperedSignature_throwsUnifiedNotFound() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "HolderStatusTampered/v1",
                "holder-status-tampered",
                1,
                60,
                Map.of("field", "value"),
                List.of(),
                null));
    String tampered = bareJwt(issued) + "tampered";

    assertThatThrownBy(() -> credentialService.holderStatus(tampered))
        .isInstanceOf(NotFoundException.class);
  }

  /** The stored credential's bare compact JWT — no disclosures, exactly what D3 expects. */
  private static String bareJwt(IssueResponse issued) {
    return issued.sdJwt().split("~")[0];
  }
}
