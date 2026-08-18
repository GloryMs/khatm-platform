package sy.khatm.platform.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.key.api.TenantKeyProvisioner;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.KhatmException;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.status.api.StatusListAllocator;
import sy.khatm.platform.support.IntegrationTestSupport;
import sy.khatm.platform.tenant.api.TenantAdmin;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantView;

/**
 * KH-2.1-BE Part A — the tenant admin/onboarding plane's domain behaviour: full onboarding (tenant
 * row + key + default status list), the resumable-create design (spec V3), duplicate-slug conflict
 * once fully onboarded, slug-format validation, status flips, and an audit row for every write.
 * Service-level (no HTTP) — the HTTP wiring and scope gate are covered by {@code
 * rbac.TenantAdminGateTest}.
 */
class TenantAdminServiceTest extends IntegrationTestSupport {

  @Autowired private TenantAdmin admin;
  @Autowired private TenantDirectory directory;
  @Autowired private TenantKeyProvisioner keyProvisioner;
  @Autowired private StatusListAllocator statusLists;
  @Autowired private JdbcTemplate jdbc;

  private static String uniqueSlug(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private int auditCount(String action, String slug) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_ref = ?",
        Integer.class,
        action,
        slug);
  }

  @Test
  void create_onboardsFreshTenant_withKeyAndStatusList_andAuditRow() {
    String slug = uniqueSlug("adminsvc-create");

    TenantView view =
        admin.create(slug, new LocalizedText("Acme Gov", "أكمي الحكومية"), "GOVERNMENT", null);

    assertThat(view.slug()).isEqualTo(slug);
    assertThat(view.status()).isEqualTo("ACTIVE");
    assertThat(view.deployMode()).isEqualTo("SAAS");
    assertThat(view.nameI18n().en()).isEqualTo("Acme Gov");
    assertThat(auditCount("TENANT_CREATED", slug)).isEqualTo(1);

    // issuer_key/status_list are RLS-protected and this test's own ambient tenant is never the
    // freshly onboarded one — same reasoning as TenantAdminService#create's own internal check.
    TenantContext.set(view.id(), slug);
    try {
      assertThat(keyProvisioner.hasActiveKey(view.id())).isTrue();
      Integer listRows =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM status_list WHERE tenant_id = ?", Integer.class, view.id());
      assertThat(listRows).isEqualTo(1);
    } finally {
      TenantContext.clear();
    }

    assertThat(directory.findBySlug(slug)).isPresent();
    assertThat(directory.findById(view.id())).isPresent();
  }

  @Test
  void create_sameSlugTwice_onceFullyOnboarded_conflicts_andLeavesExactlyOneRow() {
    String slug = uniqueSlug("adminsvc-dup");
    admin.create(slug, new LocalizedText("First", "الأول"), "PRIVATE", null);

    assertThatThrownBy(
            () -> admin.create(slug, new LocalizedText("Second", "الثاني"), "PRIVATE", null))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_TNT_0409);

    Integer rows =
        jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE slug = ?", Integer.class, slug);
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void create_slugExistsWithNoActiveKeyYet_resumesOnboarding_insteadOfConflicting() {
    // Simulate a prior onboarding attempt that died between "tenant row created" and "key
    // provisioned" (spec V3) by inserting the row directly, bypassing the service.
    String slug = uniqueSlug("adminsvc-resume");
    UUID id = Uuidv7.generate();
    jdbc.update(
        "INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status, created_at,"
            + " updated_at) VALUES (?, ?, ?::jsonb, ?, ?, ?, now(), now())",
        id,
        slug,
        "{\"en\":\"Partial\",\"ar\":\"جزئي\"}",
        "OTHER",
        "SAAS",
        "ACTIVE");
    // issuer_key is RLS-protected and this test's own ambient tenant is never this freshly
    // inserted one — same reasoning as TenantAdminService#create's own internal check.
    TenantContext.set(id, slug);
    try {
      assertThat(keyProvisioner.hasActiveKey(id)).isFalse();
    } finally {
      TenantContext.clear();
    }

    TenantView resumed = admin.create(slug, new LocalizedText("Resumed", "مستأنف"), "OTHER", null);

    assertThat(resumed.id()).isEqualTo(id);
    TenantContext.set(id, slug);
    try {
      assertThat(keyProvisioner.hasActiveKey(id)).isTrue();
      Integer listRows =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM status_list WHERE tenant_id = ?", Integer.class, id);
      assertThat(listRows).isEqualTo(1);
    } finally {
      TenantContext.clear();
    }
    // Resuming does not re-record TENANT_CREATED — the row already existed.
    assertThat(auditCount("TENANT_CREATED", slug)).isEqualTo(0);

    Integer rows =
        jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE slug = ?", Integer.class, slug);
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void create_invalidSlugFormat_isRejected() {
    assertThatThrownBy(
            () -> admin.create("Not A Slug!", new LocalizedText("x", "x"), "OTHER", null))
        .isInstanceOf(ValidationException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_TNT_0400);
  }

  @Test
  void get_unknownTenant_is404() {
    assertThatThrownBy(() -> admin.get(UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_TNT_0404);
  }

  @Test
  void suspendThenActivate_flipsStatus_withAuditRows() {
    String slug = uniqueSlug("adminsvc-status");
    TenantView created = admin.create(slug, new LocalizedText("Toggle", "تبديل"), "OTHER", null);
    UUID id = created.id();

    TenantView suspended = admin.suspend(id);
    assertThat(suspended.status()).isEqualTo("SUSPENDED");
    assertThat(auditCount("TENANT_SUSPENDED", slug)).isEqualTo(1);
    assertThat(directory.findById(id).orElseThrow().isActive()).isFalse();

    TenantView reactivated = admin.activate(id);
    assertThat(reactivated.status()).isEqualTo("ACTIVE");
    assertThat(auditCount("TENANT_ACTIVATED", slug)).isEqualTo(1);
    assertThat(directory.findById(id).orElseThrow().isActive()).isTrue();
  }

  @Test
  void suspend_isIdempotent_secondCallWritesNoNewAuditRow() {
    String slug = uniqueSlug("adminsvc-idemsuspend");
    UUID id = admin.create(slug, new LocalizedText("Idem", "متكرر"), "OTHER", null).id();

    admin.suspend(id);
    admin.suspend(id);

    assertThat(auditCount("TENANT_SUSPENDED", slug)).isEqualTo(1);
  }

  @Test
  void list_includesCreatedTenant_newestFirst() {
    String older = uniqueSlug("adminsvc-list-older");
    String newer = uniqueSlug("adminsvc-list-newer");
    admin.create(older, new LocalizedText("Older", "أقدم"), "OTHER", null);
    admin.create(newer, new LocalizedText("Newer", "أحدث"), "OTHER", null);

    List<String> slugs = admin.list().stream().map(TenantView::slug).toList();
    assertThat(slugs).contains(older, newer);
    assertThat(slugs.indexOf(newer)).isLessThan(slugs.indexOf(older));
  }

  // ── KH-2.6a hierarchy (spec FS-2.5 §2/§7) ──────────────────────────────

  @Test
  void setParent_links_reflectedInView_andAuditRow() {
    String parentSlug = uniqueSlug("hier-parent");
    TenantView parent =
        admin.create(parentSlug, new LocalizedText("Parent", "الأب"), "OTHER", null);
    String childSlug = uniqueSlug("hier-child");
    TenantView child = admin.create(childSlug, new LocalizedText("Child", "الابن"), "OTHER", null);

    TenantView linked = admin.setParent(child.id(), parentSlug);

    assertThat(linked.parentSlug()).isEqualTo(parentSlug);
    assertThat(linked.parentNameI18n().en()).isEqualTo("Parent");
    assertThat(admin.get(child.id()).parentSlug()).isEqualTo(parentSlug);
    assertThat(auditCount("TENANT_PARENT_LINKED", childSlug)).isEqualTo(1);
    assertThat(parent.parentSlug()).isNull();
  }

  @Test
  void setParent_nullSlug_unlinksExistingParent_withAuditRow() {
    String parentSlug = uniqueSlug("hier-unlink-parent");
    admin.create(parentSlug, new LocalizedText("Parent", "الأب"), "OTHER", null);
    UUID childId =
        admin
            .create(
                uniqueSlug("hier-unlink-child"), new LocalizedText("Child", "الابن"), "OTHER", null)
            .id();
    admin.setParent(childId, parentSlug);

    TenantView unlinked = admin.setParent(childId, null);

    assertThat(unlinked.parentSlug()).isNull();
    assertThat(unlinked.parentNameI18n()).isNull();
    assertThat(auditCount("TENANT_PARENT_UNLINKED", admin.get(childId).slug())).isEqualTo(1);
  }

  @Test
  void setParent_selfBySlug_isRejected() {
    String slug = uniqueSlug("hier-self");
    UUID id = admin.create(slug, new LocalizedText("Self", "ذات"), "OTHER", null).id();

    assertThatThrownBy(() -> admin.setParent(id, slug))
        .isInstanceOf(ValidationException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_TNT_0422);
  }

  @Test
  void setParent_cycle_isRejected() {
    String aSlug = uniqueSlug("hier-cycle-a");
    UUID aId = admin.create(aSlug, new LocalizedText("A", "أ"), "OTHER", null).id();
    String bSlug = uniqueSlug("hier-cycle-b");
    UUID bId = admin.create(bSlug, new LocalizedText("B", "ب"), "OTHER", null).id();
    admin.setParent(bId, aSlug); // B's parent is A

    // A ← B ← A: linking A under B would create a cycle.
    assertThatThrownBy(() -> admin.setParent(aId, bSlug))
        .isInstanceOf(ValidationException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_TNT_1422);
  }

  @Test
  void setParent_depthExceeded_isRejected() {
    String rootSlug = uniqueSlug("hier-depth-root");
    UUID rootId = admin.create(rootSlug, new LocalizedText("Root", "جذر"), "OTHER", null).id();
    String midSlug = uniqueSlug("hier-depth-mid");
    UUID midId = admin.create(midSlug, new LocalizedText("Mid", "وسط"), "OTHER", null).id();
    admin.setParent(midId, rootSlug); // depth 2
    String leafSlug = uniqueSlug("hier-depth-leaf");
    UUID leafId = admin.create(leafSlug, new LocalizedText("Leaf", "ورقة"), "OTHER", null).id();
    admin.setParent(leafId, midSlug); // depth 3 — the maximum (spec §7)

    String tooDeepSlug = uniqueSlug("hier-depth-toodeep");
    UUID tooDeepId =
        admin.create(tooDeepSlug, new LocalizedText("TooDeep", "أعمق"), "OTHER", null).id();

    assertThatThrownBy(() -> admin.setParent(tooDeepId, leafSlug))
        .isInstanceOf(ValidationException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_TNT_2422);
  }

  @Test
  void setParent_suspendedParent_isRejected() {
    String parentSlug = uniqueSlug("hier-suspended-parent");
    UUID parentId =
        admin.create(parentSlug, new LocalizedText("Parent", "الأب"), "OTHER", null).id();
    admin.suspend(parentId);
    UUID childId =
        admin
            .create(
                uniqueSlug("hier-suspended-child"),
                new LocalizedText("Child", "الابن"),
                "OTHER",
                null)
            .id();

    assertThatThrownBy(() -> admin.setParent(childId, parentSlug))
        .isInstanceOf(ValidationException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_TNT_3422);
  }

  @Test
  void setParent_unknownParentSlug_is404() {
    UUID childId =
        admin
            .create(
                uniqueSlug("hier-unknown-parent"),
                new LocalizedText("Child", "الابن"),
                "OTHER",
                null)
            .id();

    assertThatThrownBy(() -> admin.setParent(childId, "no-such-tenant-" + UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_TNT_0404);
  }

  @Test
  void suspend_parentWithActiveChild_isRejected_thenSucceedsOnceChildIsNotActive() {
    String parentSlug = uniqueSlug("hier-suspend-guard-parent");
    UUID parentId =
        admin.create(parentSlug, new LocalizedText("Parent", "الأب"), "OTHER", null).id();
    UUID childId =
        admin
            .create(
                uniqueSlug("hier-suspend-guard-child"),
                new LocalizedText("Child", "الابن"),
                "OTHER",
                null)
            .id();
    admin.setParent(childId, parentSlug);

    assertThatThrownBy(() -> admin.suspend(parentId))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((KhatmException) e).errorCode())
        .isEqualTo(ErrorCode.KH_TNT_1409);
    assertThat(admin.get(parentId).status()).isEqualTo("ACTIVE");

    admin.suspend(childId);
    TenantView suspendedParent = admin.suspend(parentId);
    assertThat(suspendedParent.status()).isEqualTo("SUSPENDED");
  }

  @Test
  void ensureList_calledTwice_createsOnlyOneRow() {
    String slug = uniqueSlug("adminsvc-ensurelist");
    UUID id = admin.create(slug, new LocalizedText("List", "قائمة"), "OTHER", null).id();
    String listCode = slug + "-extra";

    // status_list is RLS-protected and this test's own ambient tenant is never the freshly
    // onboarded one — same reason TenantAdminService#create itself sets this before provisioning.
    TenantContext.set(id, slug);
    try {
      statusLists.ensureList(id, listCode);
      statusLists.ensureList(id, listCode);

      Integer rows =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM status_list WHERE tenant_id = ? AND list_code = ?",
              Integer.class,
              id,
              listCode);
      assertThat(rows).isEqualTo(1);
    } finally {
      TenantContext.clear();
    }
  }
}
