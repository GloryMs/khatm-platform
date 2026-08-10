package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.persistence.CredentialRepository;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.4 §6 DoD #1 — the flagship structural P1 test: after issuing a document with demo claim
 * fields, the JSON payload decoded from the <em>persisted</em> {@code credential.signed_payload}
 * contains no {@code claims_def} key and no claim value anywhere — only the D3 structural fields
 * plus {@code _sd}/{@code _sd_alg}. This makes P1 a mathematical property of the token's structure,
 * not a storage-policy promise (spec FS-0.4 §1).
 */
class SdJwtIssuanceStructuralTest extends IntegrationTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> EXPECTED_STRUCTURAL_KEYS =
      Set.of("iss", "vct", "ref", "status", "iat", "nbf", "exp", "_sd", "_sd_alg");

  @Autowired private CredentialService credentialService;
  @Autowired private CredentialRepository credentialRepository;

  @Test
  void issue_persistedSignedPayload_containsNoClaimsDefKeysOrValues() throws Exception {
    Map<String, Object> claims =
        Map.of(
            "nationalIdMasked", "***-***-1234",
            "fullNameAr", "أحمد سالم",
            "birthDate", "1990-05-12");
    List<String> sdFields = List.of("birthDate");

    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "StructuralProbe/v1", "holder-structural-probe", 1, 60, claims, sdFields, null));

    String compactJwt =
        credentialRepository
            .findById(UUID.fromString(issued.id()))
            .orElseThrow()
            .getSignedPayload();
    String payloadJson = decodePayload(compactJwt);

    // Direct textual assertion (spec wording): none of the plaintext values may appear anywhere
    // in the decoded payload text.
    for (Object value : claims.values()) {
      assertThat(payloadJson).doesNotContain(String.valueOf(value));
    }
    // None of the claims_def field names may appear as a JSON key either.
    for (String fieldName : claims.keySet()) {
      assertThat(payloadJson).doesNotContain("\"" + fieldName + "\":");
    }

    JsonNode payload = JSON.readTree(payloadJson);
    Set<String> actualKeys = new HashSet<>();
    payload.fieldNames().forEachRemaining(actualKeys::add);
    assertThat(actualKeys).isEqualTo(EXPECTED_STRUCTURAL_KEYS);
  }

  private static String decodePayload(String compactJwt) {
    String[] parts = compactJwt.split("\\.");
    return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
  }
}
