package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-2.6a, spec FS-2.5 §7 — {@code V16__tenant_hierarchy.sql}'s trigger is the DB-level backstop
 * for the depth/cycle invariant {@code TenantAdminService#setParent} already enforces in the normal
 * path (defense in depth, the same posture {@code V7__rls_policies.sql} already established for
 * tenant isolation). These tests write {@code tenant} rows directly via {@link JdbcTemplate},
 * bypassing the service layer entirely, to prove the trigger itself — not the service — is what
 * actually stops a violation. Each {@code jdbc} call here runs in its own fresh physical
 * transaction ({@code TransactionalTestJdbcTemplateConfig}), so one call's rollback never poisons
 * the next.
 */
class TenantHierarchyDbTriggerTest extends IntegrationTestSupport {

  @Autowired private JdbcTemplate jdbc;

  private static String uniqueSlug(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private UUID insertRoot(String slug) {
    UUID id = Uuidv7.generate();
    jdbc.update(
        "INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status, created_at,"
            + " updated_at) VALUES (?, ?, ?::jsonb, ?, ?, ?, now(), now())",
        id,
        slug,
        "{\"en\":\"T\",\"ar\":\"ت\"}",
        "OTHER",
        "SAAS",
        "ACTIVE");
    return id;
  }

  private UUID insertChild(String slug, UUID parentId) {
    UUID id = Uuidv7.generate();
    jdbc.update(
        "INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status, created_at,"
            + " updated_at, parent_tenant_id) VALUES (?, ?, ?::jsonb, ?, ?, ?, now(), now(), ?)",
        id,
        slug,
        "{\"en\":\"T\",\"ar\":\"ت\"}",
        "OTHER",
        "SAAS",
        "ACTIVE",
        parentId);
    return id;
  }

  @Test
  void insertingPastMaxDepth_directlyViaSql_isRejectedByTheTrigger() {
    UUID root = insertRoot(uniqueSlug("dbtrig-root"));
    UUID mid = insertChild(uniqueSlug("dbtrig-mid"), root);
    UUID leaf = insertChild(uniqueSlug("dbtrig-leaf"), mid); // depth 3 — the maximum (spec §7)

    assertThatThrownBy(() -> insertChild(uniqueSlug("dbtrig-toodeep"), leaf))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void updatingParentToCreateACycle_directlyViaSql_isRejectedByTheTrigger() {
    UUID a = insertRoot(uniqueSlug("dbtrig-cycle-a"));
    UUID b = insertChild(uniqueSlug("dbtrig-cycle-b"), a);

    assertThatThrownBy(
            () -> jdbc.update("UPDATE tenant SET parent_tenant_id = ? WHERE id = ?", b, a))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void selfParent_directlyViaSql_isRejectedByTheCheckConstraint() {
    UUID id = insertRoot(uniqueSlug("dbtrig-self"));

    assertThatThrownBy(
            () -> jdbc.update("UPDATE tenant SET parent_tenant_id = ? WHERE id = ?", id, id))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
