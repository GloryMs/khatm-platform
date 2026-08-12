package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.schema.api.ClaimFieldRequest;
import sy.khatm.platform.schema.api.SchemaAuthoringRequest;
import sy.khatm.platform.schema.api.SchemaCreateRequest;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.schema.domain.SchemaAuthoringService;

/**
 * Live-diagnostic regression for the "still fails after the fix" report: reproduces the exact
 * console request shape (raw JSON body, {@code schemaCode} + {@code schemaId} both present, real
 * authenticated console session over real HTTP) rather than calling {@code CredentialService#issue}
 * directly in Java, to isolate whether the gap is in Spring's HTTP/JSON layer specifically (Jackson
 * creator resolution, Bean Validation, the controller) as opposed to the service logic already
 * covered by {@code credential.domain.IssuanceSchemaVersionPinTest}.
 */
class IssueWithSchemaIdOverHttpTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private SchemaAuthoringService authoring;

  @Test
  void issue_overHttp_withSchemaIdPinnedToV2_acceptsV2Pattern() throws Exception {
    String code = "HttpSchemaPin/v1";
    SchemaDetail v1 =
        authoring.create(
            new SchemaCreateRequest(
                code,
                Map.of("en", "Http Schema Pin", "ar", "فحص"),
                List.of(
                    new ClaimFieldRequest(
                        "test_field", "text", Map.of("en", "Test", "ar", "فحص"), "[0-9]")),
                List.of(),
                1,
                null,
                null));
    authoring.publish(v1.id());
    SchemaDetail v2 =
        authoring.createVersion(
            v1.id(),
            new SchemaAuthoringRequest(
                Map.of("en", "Http Schema Pin", "ar", "فحص"),
                List.of(
                    new ClaimFieldRequest(
                        "test_field", "text", Map.of("en", "Test", "ar", "فحص"), "[0-9]{9}")),
                List.of(),
                1,
                null,
                null));
    authoring.publish(v2.id());

    SessionTestSupport.AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaCode", code);
    body.put("schemaId", v2.id().toString());
    body.put("holderRef", "holder-http-schema-pin");
    body.put("claims", Map.of("test_field", "123456789"));
    body.put("sdFields", List.of());

    ResponseEntity<String> response =
        SessionTestSupport.post(rest, "/api/v1/credentials/issue", session, body);

    assertThat(response.getStatusCode())
        .as("response body: %s", response.getBody())
        .isEqualTo(HttpStatus.OK);
    JsonNode responseBody = JSON.readTree(response.getBody());
    assertThat(responseBody.get("id").asText()).isNotBlank();
  }
}
