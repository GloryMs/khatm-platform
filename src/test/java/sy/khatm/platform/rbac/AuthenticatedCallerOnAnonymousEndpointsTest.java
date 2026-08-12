package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.ClaimCodeIssued;
import sy.khatm.platform.credential.domain.CredentialService;

/**
 * Regression coverage for a bug found live post-KH-2.4-BE: {@code /api/v1/credentials/verify} and
 * {@code /api/v1/claims/redeem} are {@code permitAll} (spec FS-0.6b DoD #9, FS-1.2.1 DoD #7) and
 * both write an {@code audit_log} row attributed to the default tenant via {@code
 * shared.TenantContext#runAsDefaultTenant}. {@code PublicEndpointsNoCredentialsTest} and {@code
 * credential.web.ClaimControllerHttpTest} already prove these paths work with zero credentials —
 * but a real browser with a live console session still attaches its {@code KHATM_SESSION} cookie to
 * a same-origin call to either endpoint, which resolves to a real authenticated principal instead
 * of an anonymous one. Before the fix, that made {@code shared.TenantContext#current()}'s fail-fast
 * guard throw {@code IllegalStateException} (surfaced to the caller as {@code KH-SYS-0500}) the
 * moment either endpoint's audit write ran — neither path had ever been exercised from an
 * authenticated session before, only via {@code curl}/{@code TestRestTemplate} with no cookies at
 * all.
 */
class AuthenticatedCallerOnAnonymousEndpointsTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private CredentialService credentialService;

  @Test
  void verify_withAuthenticatedConsoleSession_returns200_notServerError() throws Exception {
    SessionTestSupport.AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            "/api/v1/credentials/verify",
            session,
            Map.of("sdJwt", "not-a-real-jwt-authenticated-caller"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("valid").asBoolean()).isFalse();
  }

  @Test
  void claimsRedeem_validCode_withAuthenticatedConsoleSession_returns200_notServerError()
      throws Exception {
    SessionTestSupport.AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "AuthenticatedRedeemProbe/v1",
                "holder-authenticated-redeem-" + UUID.randomUUID(),
                1,
                60,
                Map.of("result", "X"),
                List.of(),
                null));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest, "/api/v1/claims/redeem", session, Map.of("code", claimCode.code()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("ref").asText()).isEqualTo(issued.ref());
  }
}
