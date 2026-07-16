package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.authlete.sd.Disclosure;
import com.authlete.sd.SDJWT;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.api.VerifyResponse;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.4 §6 DoD #2 (full round-trip), #3 (selective disclosure), and #4 (the four tamper rejections
 * — D8), plus the zero-disclosure-presentation behavior documented in spec §5.
 *
 * <p>Every test issues its own credential with one mandatory field ({@code mandatoryField}, not in
 * {@code sdFields}) and one withholdable field ({@code optionalField}, in {@code sdFields}) — D2's
 * mandatory/optional split is exercised directly rather than mocked.
 */
class SdJwtVerificationTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;

  @Test
  void verify_fullPresentation_roundTripsAllDisclosedClaims() throws Exception {
    IssueResponse issued = issueDemoCredential();

    VerifyResponse result = credentialService.verify(issued.sdJwt());

    assertThat(result.valid()).isTrue();
    assertThat(result.reason()).isEqualTo("valid");
    assertThat(result.claims())
        .containsEntry("mandatoryField", "M1")
        .containsEntry("optionalField", "O1");
  }

  @Test
  void verify_mandatoryPlusNoOptional_selectiveDisclosureSucceeds_hidesWithheldField()
      throws Exception {
    IssueResponse issued = issueDemoCredential();
    String presentation = withoutDisclosure(issued.sdJwt(), "optionalField");

    VerifyResponse result = credentialService.verify(presentation);

    assertThat(result.valid()).isTrue();
    assertThat(result.claims()).containsKey("mandatoryField");
    assertThat(result.claims()).doesNotContainKey("optionalField");
  }

  @Test
  void verify_tamperedDisclosureValue_rejected() throws Exception {
    IssueResponse issued = issueDemoCredential();
    String tampered = withTamperedValue(issued.sdJwt());

    VerifyResponse result = credentialService.verify(tampered);

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo("forged_disclosure");
  }

  @Test
  void verify_forgedDisclosure_digestNotInSd_rejected() throws Exception {
    IssueResponse issued = issueDemoCredential();
    String forged = withForeignDisclosureAppended(issued.sdJwt());

    VerifyResponse result = credentialService.verify(forged);

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo("forged_disclosure");
  }

  @Test
  void verify_duplicateDisclosure_rejected() throws Exception {
    IssueResponse issued = issueDemoCredential();
    String duplicated = withFirstDisclosureDuplicated(issued.sdJwt());

    VerifyResponse result = credentialService.verify(duplicated);

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo("duplicate_disclosure");
  }

  @Test
  void verify_withheldMandatoryField_rejected() throws Exception {
    IssueResponse issued = issueDemoCredential();
    String presentation = withoutDisclosure(issued.sdJwt(), "mandatoryField");

    VerifyResponse result = credentialService.verify(presentation);

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo("withheld_mandatory_claim");
  }

  @Test
  void verify_bareCompactJwt_isZeroDisclosurePresentation_notMalformed() throws Exception {
    IssueResponse issued = issueDemoCredential();
    String compactJwtOnly = SDJWT.parse(issued.sdJwt()).getCredentialJwt();

    VerifyResponse result = credentialService.verify(compactJwtOnly);

    // Spec FS-0.4 §5: a bare JWT is a valid zero-disclosure presentation, not malformed — it
    // fails the mandatory-disclosure check instead, since mandatoryField was never presented.
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo("withheld_mandatory_claim");
  }

  private IssueResponse issueDemoCredential() throws Exception {
    Map<String, Object> claims = Map.of("mandatoryField", "M1", "optionalField", "O1");
    List<String> sdFields = List.of("optionalField");
    return credentialService.issue(
        new IssueRequest("VerifyProbe/v1", "holder-verify-probe", 5, 60, claims, sdFields));
  }

  private static String withoutDisclosure(String presentation, String claimNameToDrop) {
    SDJWT parsed = SDJWT.parse(presentation);
    List<Disclosure> kept =
        parsed.getDisclosures().stream()
            .filter(d -> !claimNameToDrop.equals(d.getClaimName()))
            .collect(Collectors.toList());
    return new SDJWT(parsed.getCredentialJwt(), kept).toString();
  }

  private static String withTamperedValue(String presentation) {
    SDJWT parsed = SDJWT.parse(presentation);
    List<Disclosure> disclosures = new ArrayList<>(parsed.getDisclosures());
    Disclosure original = disclosures.get(0);
    Disclosure tampered =
        new Disclosure(
            original.getSalt(), original.getClaimName(), "TAMPERED-" + original.getClaimValue());
    disclosures.set(0, tampered);
    return new SDJWT(parsed.getCredentialJwt(), disclosures).toString();
  }

  private static String withForeignDisclosureAppended(String presentation) {
    SDJWT parsed = SDJWT.parse(presentation);
    List<Disclosure> disclosures = new ArrayList<>(parsed.getDisclosures());
    disclosures.add(new Disclosure("foreignField", "foreignValue"));
    return new SDJWT(parsed.getCredentialJwt(), disclosures).toString();
  }

  private static String withFirstDisclosureDuplicated(String presentation) {
    SDJWT parsed = SDJWT.parse(presentation);
    List<Disclosure> disclosures = new ArrayList<>(parsed.getDisclosures());
    disclosures.add(disclosures.get(0));
    return new SDJWT(parsed.getCredentialJwt(), disclosures).toString();
  }
}
