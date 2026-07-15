package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.api.VerifyResponse;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.5 §8 DoD #1 (kid propagates through the credential issuance path) and #3 (unknown {@code
 * kid} is rejected with no fallback), exercised end to end through {@link CredentialService} — the
 * real consumer of {@code key :: api}.
 */
class CredentialSigningAndVerificationTest extends IntegrationTestSupport {

  private static final Pattern KID_FORMAT = Pattern.compile("^[a-z0-9-]+:key-\\d+$");

  @Autowired private CredentialService credentialService;

  @Test
  void issue_jwsHeaderCarriesAWellFormedKid() throws Exception {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest("GenericDocument/v1", "holder-kid-test", 1, 60, Map.of()));

    SignedJWT parsed = SignedJWT.parse(issued.jwt());
    String kid = parsed.getHeader().getKeyID();

    assertThat(kid).isNotBlank();
    assertThat(KID_FORMAT.matcher(kid).matches())
        .as("kid '%s' matches {slug}:key-{seq}", kid)
        .isTrue();
  }

  @Test
  void verify_tokenSignedByKeyOutsideRegistry_rejectedAsBadSignature_noFallback() throws Exception {
    // A key that was never generated through KeyLifecycleService — its kid cannot possibly be in
    // issuer_key. Proves resolution is strict-by-kid with no "try the current key instead."
    var rogueKey = new ECKeyGenerator(Curve.P_256).keyID("rogue-tenant:key-1").generate();
    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType.JWT)
            .keyID("rogue-tenant:key-1")
            .build();
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .claim("ref", "ROGUE-2026-000001")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)))
            .build();
    SignedJWT rogue = new SignedJWT(header, claims);
    rogue.sign(new ECDSASigner(rogueKey));

    VerifyResponse result = credentialService.verify(rogue.serialize());

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo("bad_signature");
  }
}
