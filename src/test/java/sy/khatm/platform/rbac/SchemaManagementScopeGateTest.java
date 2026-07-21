package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.CreatedApiKey;

/**
 * KH-1.1.1 — every {@code POST}/{@code PUT} under {@code /api/v1/schemas/**} (create, update,
 * publish, version, archive) requires the {@code admin} scope, any actor kind (session or API key)
 * — the same rule {@code /api/v1/admin/**} already uses.
 */
class SchemaManagementScopeGateTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ApiKeyService apiKeyService;

  @Test
  void create_withNoCredential_returns401() {
    ResponseEntity<String> response =
        rest.postForEntity("/api/v1/schemas", createBody("GateNoCred/v1"), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void create_withApiKeyMissingAdminScope_returns403() throws Exception {
    CreatedApiKey issuerKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));

    ResponseEntity<String> response = createWithApiKey(issuerKey.rawKey(), "GateNoScope/v1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void create_withApiKeyHoldingAdminScope_createsDraft() throws Exception {
    CreatedApiKey adminKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("admin"));

    ResponseEntity<String> response = createWithApiKey(adminKey.rawKey(), "GateAdminKey/v1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("status").asText()).isEqualTo("DRAFT");
  }

  @Test
  void fullLifecycle_withAdminSession_createUpdatePublishVersionArchive() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);

    ResponseEntity<String> created =
        SessionTestSupport.post(rest, "/api/v1/schemas", session, createBody("GateLifecycle/v1"));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode createdBody = JSON.readTree(created.getBody());
    String id = createdBody.get("id").asText();
    assertThat(createdBody.get("status").asText()).isEqualTo("DRAFT");

    ResponseEntity<String> updated =
        rest.exchange(
            "/api/v1/schemas/" + id,
            HttpMethod.PUT,
            session.writeHeaders(authoringBody("Updated Lifecycle Name")),
            String.class);
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(updated.getBody()).get("nameI18n").get("en").asText())
        .isEqualTo("Updated Lifecycle Name");

    ResponseEntity<String> published =
        SessionTestSupport.post(rest, "/api/v1/schemas/" + id + "/publish", session, null);
    assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(published.getBody()).get("status").asText()).isEqualTo("PUBLISHED");

    // PUBLISHED is now immutable in place.
    ResponseEntity<String> updateAfterPublish =
        rest.exchange(
            "/api/v1/schemas/" + id,
            HttpMethod.PUT,
            session.writeHeaders(authoringBody("Should Not Apply")),
            String.class);
    assertThat(updateAfterPublish.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(JSON.readTree(updateAfterPublish.getBody()).get("code").asText())
        .isEqualTo("KH-SCH-0409");

    ResponseEntity<String> version =
        SessionTestSupport.post(
            rest,
            "/api/v1/schemas/" + id + "/versions",
            session,
            authoringBody("Version Two Name"));
    assertThat(version.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode versionBody = JSON.readTree(version.getBody());
    assertThat(versionBody.get("version").asInt()).isEqualTo(2);
    assertThat(versionBody.get("status").asText()).isEqualTo("DRAFT");

    ResponseEntity<String> archived =
        SessionTestSupport.post(rest, "/api/v1/schemas/" + id + "/archive", session, null);
    assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(archived.getBody()).get("status").asText()).isEqualTo("ARCHIVED");

    // ARCHIVED cannot be archived again.
    ResponseEntity<String> doubleArchive =
        SessionTestSupport.post(rest, "/api/v1/schemas/" + id + "/archive", session, null);
    assertThat(doubleArchive.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(JSON.readTree(doubleArchive.getBody()).get("code").asText())
        .isEqualTo("KH-SCH-1409");
  }

  @Test
  void list_withStatusFilter_showsOnlyMatchingStatus() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    ResponseEntity<String> created =
        SessionTestSupport.post(rest, "/api/v1/schemas", session, createBody("GateFilter/v1"));
    String id = JSON.readTree(created.getBody()).get("id").asText();

    ResponseEntity<String> draftList =
        SessionTestSupport.get(rest, "/api/v1/schemas?status=DRAFT", session);
    assertThat(draftList.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(containsId(draftList.getBody(), id)).isTrue();

    ResponseEntity<String> publishedList =
        SessionTestSupport.get(rest, "/api/v1/schemas?status=PUBLISHED", session);
    assertThat(publishedList.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(containsId(publishedList.getBody(), id)).isFalse();
  }

  private ResponseEntity<String> createWithApiKey(String rawKey, String code) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
    return rest.exchange(
        "/api/v1/schemas",
        HttpMethod.POST,
        new HttpEntity<>(createBody(code), headers),
        String.class);
  }

  private static Map<String, Object> createBody(String code) {
    return Map.of(
        "code", code,
        "nameI18n", Map.of("en", "Gate Probe", "ar", "فحص البوابة"),
        "claimsDef", List.of(claimField("name")),
        "sdFields", List.of(),
        "defaultMaxUses", 1);
  }

  private static Map<String, Object> authoringBody(String nameEn) {
    return Map.of(
        "nameI18n", Map.of("en", nameEn, "ar", "اسم محدث"),
        "claimsDef", List.of(claimField("name")),
        "sdFields", List.of(),
        "defaultMaxUses", 1);
  }

  private static Map<String, Object> claimField(String name) {
    return Map.of("name", name, "type", "text", "labelI18n", Map.of("en", name, "ar", name));
  }

  private static boolean containsId(String listJson, String id) throws Exception {
    for (JsonNode entry : JSON.readTree(listJson)) {
      if (id.equals(entry.get("id").asText())) {
        return true;
      }
    }
    return false;
  }
}
