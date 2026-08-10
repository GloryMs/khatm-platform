package sy.khatm.platform.schema.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.schema.api.ClaimFieldRequest;
import sy.khatm.platform.schema.api.SchemaAuthoringRequest;
import sy.khatm.platform.schema.api.SchemaCreateRequest;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-1.1.1 — schema authoring at the service level: create/update/publish/version/archive
 * transitions, the validation rules a business-level check must catch (Bean Validation on the
 * request DTOs is a controller-layer concern this suite deliberately bypasses by calling {@link
 * SchemaAuthoringService} directly), and the audit row each transition writes.
 */
class SchemaAuthoringServiceTest extends IntegrationTestSupport {

  @Autowired private SchemaAuthoringService authoring;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void create_validRequest_createsDraft_andAudits() {
    SchemaDetail created = authoring.create(createRequest("AuthoringHappy/v1"));

    assertThat(created.status()).isEqualTo("DRAFT");
    assertThat(created.code()).isEqualTo("AuthoringHappy/v1");
    assertThat(created.version()).isEqualTo(1);
    assertThat(created.sdFields()).containsExactly("age");
    assertThat(created.defaultMaxUses()).isEqualTo(1);
    assertThat(auditCount("SCHEMA_CREATED", "AuthoringHappy/v1:1")).isEqualTo(1);
  }

  @Test
  void create_defaultValiditySet_roundTripsAsIso8601Duration() {
    SchemaCreateRequest req =
        new SchemaCreateRequest(
            "AuthoringValidity/v1",
            Map.of("en", "Validity", "ar", "صلاحية"),
            List.of(field("name", "text")),
            List.of(),
            1,
            "P90D",
            null);

    SchemaDetail created = authoring.create(req);

    assertThat(created.defaultValidity()).isEqualTo("P90D");
  }

  @Test
  void create_duplicateCode_throwsValidation() {
    authoring.create(createRequest("AuthoringDup/v1"));

    assertThatThrownBy(() -> authoring.create(createRequest("AuthoringDup/v1")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void create_nameMissingArabic_throwsValidation() {
    SchemaCreateRequest req =
        new SchemaCreateRequest(
            "AuthoringBadName/v1",
            Map.of("en", "Only English"),
            List.of(field("name", "text")),
            List.of(),
            1,
            null,
            null);

    assertThatThrownBy(() -> authoring.create(req)).isInstanceOf(ValidationException.class);
  }

  @Test
  void create_unsupportedClaimType_throwsValidation() {
    SchemaCreateRequest req =
        new SchemaCreateRequest(
            "AuthoringBadType/v1",
            Map.of("en", "Bad Type", "ar", "نوع خاطئ"),
            List.of(field("photo", "image")),
            List.of(),
            1,
            null,
            null);

    assertThatThrownBy(() -> authoring.create(req)).isInstanceOf(ValidationException.class);
  }

  @Test
  void create_sdFieldNotAClaim_throwsValidation() {
    SchemaCreateRequest req =
        new SchemaCreateRequest(
            "AuthoringBadSdField/v1",
            Map.of("en", "Bad SD", "ar", "خطأ"),
            List.of(field("name", "text")),
            List.of("nonexistent"),
            1,
            null,
            null);

    assertThatThrownBy(() -> authoring.create(req)).isInstanceOf(ValidationException.class);
  }

  @Test
  void create_emptyClaimsDef_throwsValidation() {
    SchemaCreateRequest req =
        new SchemaCreateRequest(
            "AuthoringEmptyClaims/v1",
            Map.of("en", "Empty", "ar", "فارغ"),
            List.of(),
            List.of(),
            1,
            null,
            null);

    assertThatThrownBy(() -> authoring.create(req)).isInstanceOf(ValidationException.class);
  }

  @Test
  void update_draft_rewritesFields_andAudits() {
    SchemaDetail created = authoring.create(createRequest("AuthoringUpdate/v1"));

    SchemaDetail updated =
        authoring.update(
            created.id(),
            new SchemaAuthoringRequest(
                Map.of("en", "Updated Name", "ar", "اسم محدث"),
                List.of(field("name", "text"), field("age", "number")),
                List.of("age"),
                2,
                null,
                null));

    assertThat(updated.nameI18n().en()).isEqualTo("Updated Name");
    assertThat(updated.defaultMaxUses()).isEqualTo(2);
    assertThat(updated.status()).isEqualTo("DRAFT");
    assertThat(auditCount("SCHEMA_UPDATED", "AuthoringUpdate/v1:1")).isEqualTo(1);
  }

  @Test
  void update_publishedSchema_throwsImmutableAfterPublish() {
    SchemaDetail created = authoring.create(createRequest("AuthoringUpdatePublished/v1"));
    authoring.publish(created.id());

    assertThatThrownBy(
            () ->
                authoring.update(
                    created.id(),
                    new SchemaAuthoringRequest(
                        Map.of("en", "x", "ar", "س"),
                        List.of(field("name", "text")),
                        List.of(),
                        1,
                        null,
                        null)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void publish_draft_becomesPublished_andAudits() {
    SchemaDetail created = authoring.create(createRequest("AuthoringPublish/v1"));

    SchemaDetail published = authoring.publish(created.id());

    assertThat(published.status()).isEqualTo("PUBLISHED");
    assertThat(auditCount("SCHEMA_PUBLISHED", "AuthoringPublish/v1:1")).isEqualTo(1);
  }

  @Test
  void publish_alreadyPublished_throwsInvalidTransition() {
    SchemaDetail created = authoring.create(createRequest("AuthoringDoublePublish/v1"));
    authoring.publish(created.id());

    assertThatThrownBy(() -> authoring.publish(created.id())).isInstanceOf(ConflictException.class);
  }

  @Test
  void createVersion_publishedSource_createsNextDraftVersion_andAudits() {
    SchemaDetail created = authoring.create(createRequest("AuthoringVersion/v1"));
    authoring.publish(created.id());

    SchemaDetail version =
        authoring.createVersion(
            created.id(),
            new SchemaAuthoringRequest(
                Map.of("en", "Version Two", "ar", "نسخة ثانية"),
                List.of(field("name", "text")),
                List.of(),
                1,
                null,
                null));

    assertThat(version.code()).isEqualTo("AuthoringVersion/v1");
    assertThat(version.version()).isEqualTo(2);
    assertThat(version.status()).isEqualTo("DRAFT");
    assertThat(auditCount("SCHEMA_VERSION_CREATED", "AuthoringVersion/v1:2")).isEqualTo(1);
  }

  @Test
  void createVersion_draftSource_throwsInvalidTransition() {
    SchemaDetail created = authoring.create(createRequest("AuthoringVersionOfDraft/v1"));

    assertThatThrownBy(
            () ->
                authoring.createVersion(
                    created.id(),
                    new SchemaAuthoringRequest(
                        Map.of("en", "x", "ar", "س"),
                        List.of(field("name", "text")),
                        List.of(),
                        1,
                        null,
                        null)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void archive_publishedSchema_becomesArchived_andAudits() {
    SchemaDetail created = authoring.create(createRequest("AuthoringArchive/v1"));
    authoring.publish(created.id());

    SchemaDetail archived = authoring.archive(created.id());

    assertThat(archived.status()).isEqualTo("ARCHIVED");
    assertThat(auditCount("SCHEMA_ARCHIVED", "AuthoringArchive/v1:1")).isEqualTo(1);
  }

  @Test
  void archive_draftSchema_throwsInvalidTransition() {
    SchemaDetail created = authoring.create(createRequest("AuthoringArchiveDraft/v1"));

    assertThatThrownBy(() -> authoring.archive(created.id())).isInstanceOf(ConflictException.class);
  }

  private static SchemaCreateRequest createRequest(String code) {
    return new SchemaCreateRequest(
        code,
        Map.of("en", "Authoring Probe", "ar", "فحص التأليف"),
        List.of(field("name", "text"), field("age", "number")),
        List.of("age"),
        1,
        null,
        null);
  }

  private static ClaimFieldRequest field(String name, String type) {
    return new ClaimFieldRequest(name, type, Map.of("en", name, "ar", name), null);
  }

  private int auditCount(String action, String entityRef) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_ref = ?",
            Integer.class,
            action,
            entityRef);
    return count == null ? 0 : count;
  }
}
