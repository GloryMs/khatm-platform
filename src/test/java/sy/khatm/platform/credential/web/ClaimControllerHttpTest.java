package sy.khatm.platform.credential.web;

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
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.ClaimCodeIssued;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.rbac.RbacHttpTestSupport;

/**
 * Spec FS-1.2.1 DoD 1, 2, 7 at the HTTP level: the D4 response shape over the wire, the identical
 * generic {@code 404 KH-CLM-0404} envelope for a second redeem, and that the path genuinely needs
 * zero credentials (no {@code Authorization} header, no session cookie at all).
 */
class ClaimControllerHttpTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private CredentialService credentialService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void redeem_validCode_withNoCredentialsAtAll_returns200WithD4Shape_andZeroesDisclosures()
      throws Exception {
    Map<String, Object> claims = Map.of("result", "NO_RECORD", "caseNumber", "CN-HTTP-001");
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "RedeemHttpHappy/v1",
                "holder-redeem-http-happy",
                1,
                60,
                claims,
                List.of("caseNumber"),
                null));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));

    ResponseEntity<String> response =
        rest.postForEntity("/api/v1/claims/redeem", Map.of("code", claimCode.code()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("ref").asText()).isEqualTo(issued.ref());
    assertThat(body.get("credential").asText()).isNotBlank();
    assertThat(body.get("disclosures")).hasSize(2);
    assertThat(body.get("schema").get("id").asText()).isNotBlank();
    assertThat(body.get("schema").get("nameI18n").get("en").asText()).isNotBlank();
    assertThat(body.get("schema").get("version").asInt()).isEqualTo(1);
    assertThat(body.get("statusListUri").asText()).isNotBlank();
    assertThat(body.get("issuedAt").asText()).isNotBlank();
    assertThat(body.get("maxUses").asInt()).isEqualTo(1);
    assertThat(body.get("expiresAt").asText()).isNotBlank();

    Boolean disclosuresNull =
        jdbc.queryForObject(
            "SELECT disclosures_enc IS NULL FROM claim_code WHERE credential_id = ?",
            Boolean.class,
            UUID.fromString(issued.id()));
    assertThat(disclosuresNull).isTrue();
  }

  @Test
  void redeem_sameCodeTwice_secondReturns404_genericEnvelope() throws Exception {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "RedeemHttpTwice/v1",
                "holder-redeem-http-twice",
                1,
                60,
                Map.of("result", "X"),
                List.of(),
                null));
    ClaimCodeIssued claimCode =
        credentialService.issueClaimCode(
            UUID.fromString(issued.id()), issued.sdJwt(), Duration.ofMinutes(5));

    rest.postForEntity("/api/v1/claims/redeem", Map.of("code", claimCode.code()), String.class);
    ResponseEntity<String> second =
        rest.postForEntity("/api/v1/claims/redeem", Map.of("code", claimCode.code()), String.class);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertBody404(second.getBody());
  }

  @Test
  void redeem_unknownCode_withNoCredentialsAtAll_returns404_notAnAuthError() throws Exception {
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/claims/redeem", Map.of("code", "not-a-real-code"), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertBody404(response.getBody());
  }

  private static void assertBody404(String responseBody) throws Exception {
    JsonNode body = JSON.readTree(responseBody);
    assertThat(body.get("code").asText()).isEqualTo("KH-CLM-0404");
    assertThat(body.get("messageKey").asText()).isEqualTo("error.clm.invalid_or_expired");
  }
}
