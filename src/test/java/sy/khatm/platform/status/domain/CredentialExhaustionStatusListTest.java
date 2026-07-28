package sy.khatm.platform.status.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.credential.api.ConsumeRequest;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-1.6 D2 — exhaustion reuses the exact revoke bit-flip/republish path ({@code
 * status.api.StatusListRevoker}), so an exhausted credential's status-list bit must read invalid
 * after republish, exactly like {@link StatusListPublishTest}'s revoke regression pattern.
 *
 * <p>Lives in this package (not {@code credential.domain}, where {@link
 * CredentialExhaustionStatusListTest#credentialService} is a runtime dependency, not a package
 * peer) specifically to reach the package-private {@link BitstringCodec} — the "live-code
 * authority" the brief calls for: this test decodes the published artifact's bits with the exact
 * same bit-order logic production uses, rather than re-implementing MSB-first bit math a second
 * time that could quietly drift from the real one.
 */
class CredentialExhaustionStatusListTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private StatusListPublisher publisher;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void exhaustingACredential_republishesItsList_withTheBitReadingInvalid() throws Exception {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "ExhaustionStatusList/v1",
                "holder-exhaustion-statuslist",
                1,
                60,
                Map.of("field", "value"),
                null));
    UUID credentialId = UUID.fromString(issued.id());
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status_list_id, status_idx FROM credential WHERE id = ?", credentialId);
    UUID statusListId = (UUID) row.get("status_list_id");
    int statusIdx = ((Number) row.get("status_idx")).intValue();

    var response = credentialService.consume(new ConsumeRequest(issued.id(), "consumer-x", null));
    assertThat(response.consumed()).isTrue();
    assertThat(response.usesRemaining()).isZero();

    boolean published = publisher.publishIfStale(statusListId);

    assertThat(published).isTrue();
    String artifact =
        (String)
            jdbc.queryForMap("SELECT signed_artifact FROM status_list WHERE id = ?", statusListId)
                .get("signed_artifact");
    assertThat(artifact).isNotNull();
    JWTClaimsSet claims = SignedJWT.parse(artifact).getJWTClaimsSet();
    byte[] gzippedBits = Base64.getUrlDecoder().decode(claims.getStringClaim("bits"));
    assertThat(BitstringCodec.isSet(gzippedBits, statusIdx))
        .as("an exhausted credential's bit must read invalid, exactly like a revoked one")
        .isTrue();
  }
}
