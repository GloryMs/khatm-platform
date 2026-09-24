package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.schema.api.ClaimFieldRequest;
import sy.khatm.platform.schema.api.SchemaCreateRequest;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.schema.domain.SchemaAuthoringService;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-1.1-BE session brief — {@code SchemaCatalog#ensurePublished} (called from {@code
 * CredentialService#issue}) must reject a resolved existing schema that is {@code DRAFT} or {@code
 * ARCHIVED} rather than silently issuing against it, now that real authoring (KH-1.1.1) makes both
 * statuses reachable. Previously untested — {@code ensurePublished}'s only callers before this
 * session (the demo seeder) always created {@code PUBLISHED} rows directly, so the gap never
 * manifested.
 */
class IssuanceSchemaGuardTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private SchemaAuthoringService authoring;

  @Test
  void issue_againstDraftSchema_throwsInvalidTransition() {
    authoring.create(createRequest("IssuanceGuardDraft/v1"));

    assertThatThrownBy(
            () ->
                credentialService.issue(
                    new IssueRequest(
                        "IssuanceGuardDraft/v1",
                        "528c4bf6fb601543c078430aaf33f2ac6ff25e9356958e7d54e63cf640da4866",
                        1,
                        60,
                        Map.of("name", "value"),
                        List.of(),
                        null)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void issue_againstArchivedSchema_throwsInvalidTransition() {
    SchemaDetail created = authoring.create(createRequest("IssuanceGuardArchived/v1"));
    authoring.publish(created.id());
    authoring.archive(created.id());

    assertThatThrownBy(
            () ->
                credentialService.issue(
                    new IssueRequest(
                        "IssuanceGuardArchived/v1",
                        "b099380555164a917c88265eedffcbecab545f9399bb8da729ebe2ab6e22cb26",
                        1,
                        60,
                        Map.of("name", "value"),
                        List.of(),
                        null)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void issue_againstPublishedSchema_works() {
    SchemaDetail created = authoring.create(createRequest("IssuanceGuardPublished/v1"));
    authoring.publish(created.id());

    credentialService.issue(
        new IssueRequest(
            "IssuanceGuardPublished/v1",
            "c37e59bc6764f93da6bcc95a0c2d0c6856a764949ce047338d180fc93da04240",
            1,
            60,
            Map.of("name", "value"),
            List.of(),
            null));
  }

  private static SchemaCreateRequest createRequest(String code) {
    return new SchemaCreateRequest(
        code,
        Map.of("en", "Issuance Guard Probe", "ar", "فحص إصدار"),
        List.of(new ClaimFieldRequest("name", "text", Map.of("en", "Name", "ar", "الاسم"), null)),
        List.of(),
        1,
        null,
        null);
  }
}
