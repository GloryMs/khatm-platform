package sy.khatm.platform.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.authlete.sd.Disclosure;
import com.authlete.sd.SDJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.api.VerifyRequest;
import sy.khatm.platform.credential.api.VerifyResponse;

/**
 * FS-0.6a §5 DoD criteria 1 (404/500 envelope shape), 2 (Arabic vs. unsupported-language fallback),
 * 3 (Bean Validation 400 with translated {@code details[]}), 4 (/verify in both languages), and 5
 * (traceId consistency across header/envelope/logs).
 *
 * <p>Full HTTP-level test — extends {@link ErrorEnvelopeTestSupport} rather than {@code
 * IntegrationTestSupport} because it needs a real embedded servlet container ({@code
 * WebEnvironment.RANDOM_PORT}), which {@code IntegrationTestSupport} deliberately does not provide
 * (it pins {@code NONE} for its shared-context, no-HTTP test suite).
 */
class ErrorEnvelopeAndI18nTest extends ErrorEnvelopeTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern UUID_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  @Autowired private TestRestTemplate rest;

  // ── DoD #1: 404 envelope, and a synthetic 500 with the same shape ──────────────────────────

  @Test
  void get_nonexistentCredential_returns404WithFullEnvelope_noStackTrace() throws Exception {
    ResponseEntity<String> response =
        rest.getForEntity("/api/v1/credentials/" + UUID.randomUUID(), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-CRD-0404");
    assertThat(body.get("messageKey").asText()).isEqualTo("credential.not-found");
    assertThat(body.get("message").asText()).isNotBlank();
    assertThat(body.get("traceId").asText()).isNotBlank();
    assertThat(body.get("timestamp").asText()).isNotBlank();
    assertThat(body.get("details").isArray()).isTrue();
    assertThat(fieldNames(body))
        .containsExactlyInAnyOrder(
            "code", "messageKey", "message", "traceId", "timestamp", "details");
  }

  @Test
  void get_syntheticInternalFailure_returns500WithSameEnvelopeShape_genericMessage()
      throws Exception {
    ResponseEntity<String> notFound =
        rest.getForEntity("/api/v1/credentials/" + UUID.randomUUID(), String.class);
    ResponseEntity<String> boom = rest.getForEntity("/api/v1/_test/boom", String.class);

    assertThat(boom.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    JsonNode body = JSON.readTree(boom.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-SYS-0500");
    assertThat(body.get("messageKey").asText()).isEqualTo("system.unexpected-error");
    // Generic — never the internal exception's own message.
    assertThat(body.get("message").asText()).doesNotContainIgnoringCase("boom");
    assertThat(body.get("message").asText()).doesNotContainIgnoringCase("IllegalState");

    JsonNode notFoundBody = JSON.readTree(notFound.getBody());
    assertThat(fieldNames(body)).isEqualTo(fieldNames(notFoundBody));
  }

  // ── DoD #2: Arabic vs. unsupported-language fallback ────────────────────────────────────────

  @Test
  void get_nonexistentCredential_withArabicAcceptLanguage_returnsArabicMessage() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.ACCEPT_LANGUAGE, "ar");
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/credentials/" + UUID.randomUUID(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);

    JsonNode body = JSON.readTree(response.getBody());
    String message = body.get("message").asText();
    assertThat(containsArabic(message))
        .as("message '%s' should contain Arabic characters", message)
        .isTrue();
  }

  @Test
  void get_nonexistentCredential_withUnsupportedLanguage_fallsBackToEnglish_noError()
      throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.ACCEPT_LANGUAGE, "de");
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/credentials/" + UUID.randomUUID(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    JsonNode body = JSON.readTree(response.getBody());
    String message = body.get("message").asText();
    assertThat(containsArabic(message)).isFalse();
    assertThat(message).isEqualTo("Credential not found.");
  }

  @Test
  void get_nonexistentCredential_withNoAcceptLanguageHeader_defaultsToEnglish() throws Exception {
    ResponseEntity<String> response =
        rest.getForEntity("/api/v1/credentials/" + UUID.randomUUID(), String.class);

    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("message").asText()).isEqualTo("Credential not found.");
  }

  // ── DoD #3: Bean Validation 400 with translated details[] ──────────────────────────────────

  @Test
  void issue_missingHolderRef_returns400WithTranslatedDetails() throws Exception {
    Map<String, Object> requestBody =
        Map.of("schemaCode", "ValidationProbe/v1", "claims", Map.of());
    ResponseEntity<String> response =
        rest.postForEntity("/api/v1/credentials/issue", requestBody, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-SYS-0400");
    JsonNode details = body.get("details");
    assertThat(details.isArray()).isTrue();
    assertThat(details).hasSize(1);
    JsonNode detail = details.get(0);
    assertThat(detail.get("field").asText()).isEqualTo("holderRef");
    assertThat(detail.get("messageKey").asText()).isEqualTo("validation.NotBlank");
    assertThat(detail.get("message").asText()).isNotBlank();
  }

  // ── DoD #4: /verify on a tampered document, in both languages ──────────────────────────────

  @Test
  void verify_tamperedDisclosure_returns200_withLocalizedReasonMessage_inEnglishAndArabic()
      throws Exception {
    String tamperedPresentation = issueThenTamperFirstDisclosure();

    VerifyResponse enResult = postVerify(tamperedPresentation, "en");
    assertThat(enResult.valid()).isFalse();
    assertThat(enResult.reason()).isEqualTo("forged_disclosure");
    assertThat(enResult.reasonMessage()).isNotBlank();
    assertThat(containsArabic(enResult.reasonMessage())).isFalse();

    VerifyResponse arResult = postVerify(tamperedPresentation, "ar");
    assertThat(arResult.valid()).isFalse();
    assertThat(arResult.reason()).isEqualTo("forged_disclosure");
    assertThat(containsArabic(arResult.reasonMessage())).isTrue();
  }

  // ── DoD #5: traceId consistency ──────────────────────────────────────────────────────────────

  @Test
  void request_withXRequestIdHeader_sameTraceIdInHeaderEnvelopeAndLogs() throws Exception {
    String suppliedTraceId = "test-trace-" + UUID.randomUUID();
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Request-Id", suppliedTraceId);

    withCapturedLogs(
        appender -> {
          ResponseEntity<String> response =
              rest.exchange(
                  "/api/v1/credentials/" + UUID.randomUUID(),
                  HttpMethod.GET,
                  new HttpEntity<>(headers),
                  String.class);

          assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(suppliedTraceId);
          try {
            JsonNode body = JSON.readTree(response.getBody());
            assertThat(body.get("traceId").asText()).isEqualTo(suppliedTraceId);
          } catch (Exception e) {
            throw new AssertionError(e);
          }

          boolean logCapturedTraceId =
              appender.list.stream()
                  .anyMatch(
                      event -> suppliedTraceId.equals(event.getMDCPropertyMap().get("traceId")));
          assertThat(logCapturedTraceId)
              .as("expected a captured log line carrying MDC traceId=%s", suppliedTraceId)
              .isTrue();
        });
  }

  @Test
  void request_withNoXRequestIdHeader_generatesAUuid() {
    ResponseEntity<String> response =
        rest.getForEntity("/api/v1/credentials/" + UUID.randomUUID(), String.class);

    String traceId = response.getHeaders().getFirst("X-Request-Id");
    assertThat(traceId).isNotBlank();
    assertThat(UUID_PATTERN.matcher(traceId).matches())
        .as("generated traceId '%s' should look like a UUID", traceId)
        .isTrue();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private VerifyResponse postVerify(String sdJwt, String acceptLanguage) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
    HttpEntity<VerifyRequest> entity = new HttpEntity<>(new VerifyRequest(sdJwt), headers);
    ResponseEntity<VerifyResponse> response =
        rest.postForEntity("/api/v1/credentials/verify", entity, VerifyResponse.class);
    return response.getBody();
  }

  private String issueThenTamperFirstDisclosure() {
    IssueRequest issueRequest =
        new IssueRequest(
            "ErrorEnvelopeProbe/v1",
            "holder-error-envelope-probe",
            5,
            60,
            Map.of("field1", "value1", "field2", "value2"),
            List.of("field2"));
    ResponseEntity<IssueResponse> issued =
        rest.postForEntity("/api/v1/credentials/issue", issueRequest, IssueResponse.class);
    String presentation = issued.getBody().sdJwt();

    SDJWT parsed = SDJWT.parse(presentation);
    List<Disclosure> disclosures = new ArrayList<>(parsed.getDisclosures());
    Disclosure original = disclosures.get(0);
    Disclosure tampered =
        new Disclosure(
            original.getSalt(), original.getClaimName(), "TAMPERED-" + original.getClaimValue());
    disclosures.set(0, tampered);
    return new SDJWT(parsed.getCredentialJwt(), disclosures).toString();
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static boolean containsArabic(String value) {
    return value
        .chars()
        .anyMatch(cp -> Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.ARABIC);
  }
}
