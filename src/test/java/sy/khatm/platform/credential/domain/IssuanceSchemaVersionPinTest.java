package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.schema.api.ClaimFieldRequest;
import sy.khatm.platform.schema.api.SchemaAuthoringRequest;
import sy.khatm.platform.schema.api.SchemaCreateRequest;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.schema.domain.SchemaAuthoringService;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-2.4-BE follow-up hotfix — before {@code IssueRequest#schemaId} existed, {@code
 * CredentialService#issue} could only ever resolve {@code (schemaCode, version=1)}, so a schema
 * authored and published at version 2+ (KH-1.1.1's {@code createVersion}) was silently unreachable
 * from real issuance: the console let an operator pick a specific published version, but every
 * issue request still landed on version 1's claim definition regardless of which version the
 * operator actually selected. Found live issuing against a schema whose v2 tightened a claim
 * field's {@code pattern} — issuance kept validating against v1's looser pattern no matter what was
 * submitted.
 */
class IssuanceSchemaVersionPinTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private SchemaAuthoringService authoring;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void issue_withSchemaIdPinnedToV2_validatesAndStoresAgainstV2NotV1() {
    String code = "SchemaPin/v1";
    SchemaDetail v1 = authoring.create(createRequest(code, "[0-9]"));
    authoring.publish(v1.id());
    SchemaDetail v2 = authoring.createVersion(v1.id(), updateRequest("[0-9]{9}"));
    authoring.publish(v2.id());

    // Nine digits: fails v1's single-digit pattern, matches v2's exactly-nine-digits pattern —
    // the same shape of value that kept failing live until schemaId pinning existed.
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                code,
                "holder-schema-pin-v2",
                1,
                60,
                Map.of("test_field", "123456789"),
                List.of(),
                null,
                v2.id()));

    UUID storedSchemaId =
        jdbc.queryForObject(
            "SELECT schema_id FROM credential WHERE ref = ?", UUID.class, issued.ref());
    assertThat(storedSchemaId).isEqualTo(v2.id());
  }

  @Test
  void issue_withSchemaIdPinnedToV2_rejectsValueMatchingV2Pattern_notV1() {
    String code = "SchemaPinReject/v1";
    SchemaDetail v1 = authoring.create(createRequest(code, "[0-9]"));
    authoring.publish(v1.id());
    SchemaDetail v2 = authoring.createVersion(v1.id(), updateRequest("[0-9]{9}"));
    authoring.publish(v2.id());

    // Ten digits matches neither pattern, but specifically proves v2 (not v1) is what's being
    // checked: a single-digit failure here would be ambiguous about which schema was used.
    assertThatThrownBy(
            () ->
                credentialService.issue(
                    new IssueRequest(
                        code,
                        "holder-schema-pin-reject",
                        1,
                        60,
                        Map.of("test_field", "0123456789"),
                        List.of(),
                        null,
                        v2.id())))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void issue_withoutSchemaId_stillDefaultsToVersion1_unchangedBehavior() {
    String code = "SchemaPinDefault/v1";
    SchemaDetail v1 = authoring.create(createRequest(code, "[0-9]"));
    authoring.publish(v1.id());
    SchemaDetail v2 = authoring.createVersion(v1.id(), updateRequest("[0-9]{9}"));
    authoring.publish(v2.id());

    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                code,
                "holder-schema-pin-default",
                1,
                60,
                Map.of("test_field", "5"),
                List.of(),
                null));

    UUID storedSchemaId =
        jdbc.queryForObject(
            "SELECT schema_id FROM credential WHERE ref = ?", UUID.class, issued.ref());
    assertThat(storedSchemaId).isEqualTo(v1.id());
  }

  @Test
  void issue_withSchemaIdOnDraftSchema_throwsInvalidTransition() {
    SchemaDetail draft = authoring.create(createRequest("SchemaPinDraft/v1", null));

    assertThatThrownBy(
            () ->
                credentialService.issue(
                    new IssueRequest(
                        null,
                        "holder-schema-pin-draft",
                        1,
                        60,
                        Map.of("test_field", "5"),
                        List.of(),
                        null,
                        draft.id())))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void issue_withUnknownSchemaId_throwsNotFound() {
    assertThatThrownBy(
            () ->
                credentialService.issue(
                    new IssueRequest(
                        null,
                        "holder-schema-pin-unknown",
                        1,
                        60,
                        Map.of("test_field", "5"),
                        List.of(),
                        null,
                        UUID.randomUUID())))
        .isInstanceOf(NotFoundException.class);
  }

  private static SchemaCreateRequest createRequest(String code, String pattern) {
    return new SchemaCreateRequest(
        code,
        Map.of("en", "Schema Pin Probe", "ar", "فحص تثبيت المخطط"),
        List.of(
            new ClaimFieldRequest(
                "test_field", "text", Map.of("en", "Test Field", "ar", "حقل اختبار"), pattern)),
        List.of(),
        1,
        null,
        null);
  }

  private static SchemaAuthoringRequest updateRequest(String pattern) {
    return new SchemaAuthoringRequest(
        Map.of("en", "Schema Pin Probe", "ar", "فحص تثبيت المخطط"),
        List.of(
            new ClaimFieldRequest(
                "test_field", "text", Map.of("en", "Test Field", "ar", "حقل اختبار"), pattern)),
        List.of(),
        1,
        null,
        null);
  }
}
