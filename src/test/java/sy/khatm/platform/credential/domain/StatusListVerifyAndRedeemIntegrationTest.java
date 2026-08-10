package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.api.VerifyResponse;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-1.3 DoD #6 — {@code /verify}'s additive {@code statusList*} fields (D6) and the
 * claim-redeem path's real {@code statusListUri} (D7, replacing the pre-KH-1.3 placeholder).
 */
class StatusListVerifyAndRedeemIntegrationTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private ClaimRedemptionService redemptionService;

  @Test
  void verify_validUnrevokedCredential_carriesStatusListMetadata() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "StatusVerify/v1",
                "holder-status-verify",
                1,
                60,
                Map.of("field", "value"),
                List.of(),
                null));

    VerifyResponse result = credentialService.verify(issued.sdJwt());

    assertThat(result.valid()).isTrue();
    assertThat(result.statusListChecked()).isTrue();
    assertThat(result.statusListVersion()).isNotNull();
    assertThat(result.statusListUri()).matches(".*/sl/khatm-default/.+");
  }

  @Test
  void verify_revokedCredential_reflectsBitstringGroundedRevocation_withStatusListMetadata() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "StatusVerifyRevoked/v1",
                "holder-status-verify-revoked",
                1,
                60,
                Map.of("field", "value"),
                List.of(),
                null));
    long versionBeforeRevoke = credentialService.verify(issued.sdJwt()).statusListVersion();
    credentialService.revoke(UUID.fromString(issued.id()));

    VerifyResponse result = credentialService.verify(issued.sdJwt());

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo("revoked");
    assertThat(result.revoked()).isTrue();
    assertThat(result.statusListChecked()).isTrue();
    assertThat(result.statusListVersion()).isGreaterThan(versionBeforeRevoke);
  }

  @Test
  void verify_malformedPresentation_carriesNoStatusListMetadata() {
    VerifyResponse result = credentialService.verify("not-a-real-jwt");

    assertThat(result.valid()).isFalse();
    assertThat(result.statusListChecked()).isFalse();
    assertThat(result.statusListVersion()).isNull();
    assertThat(result.statusListUri()).isNull();
  }

  @Test
  void redeem_deliversRealStatusListUri_notThePreKh13Placeholder() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "StatusRedeem/v1",
                "holder-status-redeem",
                1,
                60,
                Map.of("field", "value"),
                List.of(),
                null));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));

    ClaimRedeemResult result = redemptionService.redeem(claimCode.code());

    // The pre-KH-1.3 placeholder was the raw status_list_id (a bare UUID string); the real value
    // is a fully-qualified /sl/{tenantSlug}/{listCode} URL.
    assertThat(result.statusListUri()).matches(".*/sl/khatm-default/.+");
    assertThat(result.statusListUri()).doesNotMatch("^[0-9a-fA-F-]{36}$");
  }

  /**
   * D7 also covers the SD-JWT's own embedded {@code status.status_list.uri} claim, set at issuance
   * — this is what makes offline verification (no server round trip at all) possible: an offline
   * verifier only ever has whatever the token itself carries.
   */
  @Test
  void issue_embedsRealResolvableStatusListUri_inTheSdJwtItself() throws Exception {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "StatusIssueEmbed/v1",
                "holder-status-issue-embed",
                1,
                60,
                Map.of("field", "value"),
                List.of(),
                null));

    String compactJwt = issued.sdJwt().split("~")[0];
    JWTClaimsSet claims = SignedJWT.parse(compactJwt).getJWTClaimsSet();
    @SuppressWarnings("unchecked")
    Map<String, Object> status = (Map<String, Object>) claims.getClaim("status");
    @SuppressWarnings("unchecked")
    Map<String, Object> statusList = (Map<String, Object>) status.get("status_list");
    String uri = (String) statusList.get("uri");

    assertThat(uri).matches(".*/sl/khatm-default/.+");
    assertThat(uri).doesNotMatch("^[0-9a-fA-F-]{36}$");
  }
}
