package sy.khatm.platform.consumer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.consumer.api.ConsumingPartyAdmin;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.consumer.api.ConsumingPartyView;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaDefinition;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.KhatmException;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-1.4.4 — the consuming-party admin plane's domain behaviour: create/idempotency, status flips,
 * schema allowlist referential rules, disallow-as-idempotent-no-op, code-format validation, and an
 * audit row for every write. Service-level (no HTTP) — the HTTP wiring and scope gate are covered
 * by {@code rbac.ConsumingPartyAdminGateTest}.
 */
class ConsumingPartyAdminServiceTest extends IntegrationTestSupport {

  @Autowired private ConsumingPartyAdmin admin;
  @Autowired private ConsumingPartyRegistry registry;
  @Autowired private SchemaCatalog schemas;
  @Autowired private JdbcTemplate jdbc;

  private static String uniqueCode(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private UUID ensureSchema(String code) {
    SchemaRef ref =
        schemas.ensurePublished(
            new SchemaDefinition(code, 1, new LocalizedText(code, code), "{}", List.of(), 1));
    return ref.id();
  }

  private int auditCount(String action, String code) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_ref = ?",
        Integer.class,
        action,
        code);
  }

  @Test
  void create_registersActiveParty_withAuditRow() {
    String code = uniqueCode("adminsvc-create");

    ConsumingPartyView view = admin.create(code, new LocalizedText("Acme Verifier", "المدقق"));

    assertThat(view.code()).isEqualTo(code);
    assertThat(view.status()).isEqualTo("ACTIVE");
    assertThat(view.nameI18n().en()).isEqualTo("Acme Verifier");
    assertThat(view.allowedSchemas()).isEmpty();
    assertThat(auditCount("CONSUMING_PARTY_CREATED", code)).isEqualTo(1);

    // Deterministic id: the same code resolves to the same row via the runtime ensure() path.
    assertThat(registry.ensure(code).id()).isEqualTo(view.id());
  }

  @Test
  void create_sameCodeTwice_conflicts_andLeavesExactlyOneRow() {
    String code = uniqueCode("adminsvc-dup");
    admin.create(code, new LocalizedText("First", "الأول"));

    assertThatThrownBy(() -> admin.create(code, new LocalizedText("Second", "الثاني")))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_CNS_0409);

    Integer rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM consuming_party WHERE code = ?", Integer.class, code);
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void create_invalidCodeFormat_isRejected() {
    assertThatThrownBy(() -> admin.create("Not A Slug!", new LocalizedText("x", "x")))
        .isInstanceOf(ValidationException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_CNS_0400);
  }

  @Test
  void get_unknownParty_is404() {
    assertThatThrownBy(() -> admin.get(UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_CNS_0404);
  }

  @Test
  void suspendThenActivate_flipsStatus_andToggersIsActive_withAuditRows() {
    String code = uniqueCode("adminsvc-status");
    ConsumingPartyView created = admin.create(code, new LocalizedText("Toggle", "تبديل"));
    UUID id = created.id();

    assertThat(registry.isActive(id)).isTrue();

    ConsumingPartyView suspended = admin.suspend(id);
    assertThat(suspended.status()).isEqualTo("SUSPENDED");
    assertThat(registry.isActive(id)).isFalse();
    assertThat(auditCount("CONSUMING_PARTY_SUSPENDED", code)).isEqualTo(1);

    ConsumingPartyView reactivated = admin.activate(id);
    assertThat(reactivated.status()).isEqualTo("ACTIVE");
    assertThat(registry.isActive(id)).isTrue();
    assertThat(auditCount("CONSUMING_PARTY_ACTIVATED", code)).isEqualTo(1);
  }

  @Test
  void suspend_isIdempotent_secondCallWritesNoNewAuditRow() {
    String code = uniqueCode("adminsvc-idemsuspend");
    UUID id = admin.create(code, new LocalizedText("Idem", "متكرر")).id();

    admin.suspend(id);
    admin.suspend(id);

    assertThat(auditCount("CONSUMING_PARTY_SUSPENDED", code)).isEqualTo(1);
  }

  @Test
  void allowSchema_scopesParty_withAuditRow_andShowsInView() {
    String code = uniqueCode("adminsvc-allow");
    UUID partyId = admin.create(code, new LocalizedText("Allow", "سماح")).id();
    UUID schemaId = ensureSchema(uniqueCode("AdminSvcAllowSchema") + "/v1");

    ConsumingPartyView view = admin.allowSchema(partyId, schemaId);

    assertThat(view.allowedSchemas())
        .extracting(ConsumingPartyView.AllowedSchema::schemaId)
        .containsExactly(schemaId);
    assertThat(registry.isSchemaAllowed(partyId, schemaId)).isTrue();
    assertThat(auditCount("CONSUMING_PARTY_SCHEMA_ALLOWED", code)).isEqualTo(1);
  }

  @Test
  void allowSchema_unknownParty_is404_partyCode() {
    UUID schemaId = ensureSchema(uniqueCode("AdminSvcAllowNoParty") + "/v1");
    assertThatThrownBy(() -> admin.allowSchema(UUID.randomUUID(), schemaId))
        .isInstanceOf(NotFoundException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_CNS_0404);
  }

  @Test
  void allowSchema_unknownSchema_is404_schemaCode() {
    String code = uniqueCode("adminsvc-allownoschema");
    UUID partyId = admin.create(code, new LocalizedText("x", "x")).id();
    assertThatThrownBy(() -> admin.allowSchema(partyId, UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_CNS_1404);
  }

  @Test
  void disallowSchema_isIdempotent_noOpWhenNotAllowed_removesWhenAllowed() {
    String code = uniqueCode("adminsvc-disallow");
    UUID partyId = admin.create(code, new LocalizedText("Dis", "إزالة")).id();
    UUID schemaId = ensureSchema(uniqueCode("AdminSvcDisallowSchema") + "/v1");

    // No-op: nothing to remove yet.
    assertThat(admin.disallowSchema(partyId, schemaId)).isFalse();
    assertThat(auditCount("CONSUMING_PARTY_SCHEMA_DISALLOWED", code)).isEqualTo(0);

    admin.allowSchema(partyId, schemaId);
    assertThat(admin.disallowSchema(partyId, schemaId)).isTrue();
    assertThat(registry.isSchemaAllowed(partyId, schemaId)).isFalse();
    assertThat(auditCount("CONSUMING_PARTY_SCHEMA_DISALLOWED", code)).isEqualTo(1);

    // Idempotent: disallowing again is a no-op, no second audit row.
    assertThat(admin.disallowSchema(partyId, schemaId)).isFalse();
    assertThat(auditCount("CONSUMING_PARTY_SCHEMA_DISALLOWED", code)).isEqualTo(1);
  }

  @Test
  void disallowSchema_unknownParty_isNoOpFalse() {
    assertThat(admin.disallowSchema(UUID.randomUUID(), UUID.randomUUID())).isFalse();
  }

  @Test
  void list_includesCreatedParty_newestFirst() {
    String older = uniqueCode("adminsvc-list-older");
    String newer = uniqueCode("adminsvc-list-newer");
    admin.create(older, new LocalizedText("Older", "أقدم"));
    admin.create(newer, new LocalizedText("Newer", "أحدث"));

    List<String> codes = admin.list().stream().map(ConsumingPartyView::code).toList();
    assertThat(codes).contains(older, newer);
    // Newer was created after older, so it must appear before it in the newest-first ordering.
    assertThat(codes.indexOf(newer)).isLessThan(codes.indexOf(older));
  }
}
