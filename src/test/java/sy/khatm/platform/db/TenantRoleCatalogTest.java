package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.domain.RoleCatalogSeeder;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-2.2 D5/D6 (extended KH-2.6b, spec FS-2.5 §3) — every tenant must have the fixed role
 * catalog with the exact granular scope sets, and never the retired {@code admin} scope:
 *
 * <ul>
 *   <li>{@code V12__seed_tenant_role_catalogs.sql}'s own backfill statement is idempotent and heals
 *       any tenant with no role rows at all — proven here by re-running that exact statement
 *       directly against a tenant this test creates with zero roles, rather than asserting over the
 *       whole shared-context suite (several other test classes, e.g. {@code
 *       tenant.domain.TenantAdminServiceTest}, deliberately call {@code TenantAdmin#create}
 *       directly, service-level, bypassing the rbac onboarding orchestration entirely — those
 *       tenants having no catalog is expected, not a defect V12 needs to catch). V12 predates
 *       {@code ORG_ADMIN} (added {@code V17__seed_org_admin_role.sql}, KH-2.6b) — this test's own
 *       literal copy of V12's statement is deliberately left at three roles, exactly as V12 itself
 *       shipped; it is not a test of the current full catalog.
 *   <li>{@link RoleCatalogSeeder#ensureCatalog}, the onboarding-time mirror for new tenants,
 *       produces the identical scope sets, now including {@code ORG_ADMIN}.
 * </ul>
 */
class TenantRoleCatalogTest extends IntegrationTestSupport {

  private static final String V12_BACKFILL_SQL =
      "INSERT INTO role (id, tenant_id, code, name_i18n, scopes) SELECT gen_random_uuid(), t.id,"
          + " v.code, v.name_i18n, v.scopes FROM tenant t CROSS JOIN (VALUES ('PLATFORM_ADMIN',"
          + " '{\"en\":\"Platform Administrator\",\"ar\":\"مدير المنصة\"}'::jsonb, "
          + "ARRAY['issue','verify','consume','revoke','schema:manage','consumer:manage','key:manage','tenant:admin','platform:admin']::text[]),"
          + " ('TENANT_ADMIN', '{\"en\":\"Tenant Administrator\",\"ar\":\"مدير الجهة\"}'::jsonb, "
          + "ARRAY['issue','verify','consume','revoke','schema:manage','consumer:manage','key:manage','tenant:admin']::text[]),"
          + " ('ISSUER_OPERATOR', '{\"en\":\"Issuer Operator\",\"ar\":\"مشغّل الإصدار\"}'::jsonb,"
          + " ARRAY['issue','verify','revoke']::text[])) AS v(code, name_i18n, scopes) WHERE t.id ="
          + " ? AND NOT EXISTS (SELECT 1 FROM role r WHERE r.tenant_id = t.id AND r.code = v.code)";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private RoleCatalogSeeder roleCatalogSeeder;

  @Test
  void v12BackfillStatement_healsATenantWithNoRoleRowsAtAll() {
    UUID tenantId = Uuidv7.generate();
    String slug = "v12-backfill-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status) VALUES"
            + " (?, ?, '{\"en\":\"V12\",\"ar\":\"ت\"}'::jsonb, 'GOVERNMENT', 'SAAS', 'ACTIVE')",
        tenantId,
        slug);

    // V12 itself runs as Flyway's owner/superuser role (bypasses FORCE RLS, see the migration's own
    // header comment); this test's jdbc bean is the app's RLS-bound khatm_app connection, so it
    // must
    // set TenantContext to the tenant under test the same way every other RLS-protected write in
    // this suite does.
    TenantContext.set(tenantId, slug);
    try {
      Integer beforeCount =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM role WHERE tenant_id = ?", Integer.class, tenantId);
      assertThat(beforeCount).as("freshly inserted tenant starts with no roles").isZero();

      jdbc.update(V12_BACKFILL_SQL, tenantId);

      Integer afterCount =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM role WHERE tenant_id = ?", Integer.class, tenantId);
      assertThat(afterCount).as("the backfill must insert the full 3-role catalog").isEqualTo(3);

      // Idempotent — re-running must not duplicate.
      jdbc.update(V12_BACKFILL_SQL, tenantId);
      Integer afterSecondRun =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM role WHERE tenant_id = ?", Integer.class, tenantId);
      assertThat(afterSecondRun).isEqualTo(3);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void noRoleAnywhere_carriesTheRetiredAdminScope_afterV12() {
    List<String> codes =
        jdbc.query(
            "SELECT code FROM role WHERE 'admin' = ANY(scopes)", (rs, i) -> rs.getString("code"));

    assertThat(codes).as("roles still carrying the retired 'admin' scope").isEmpty();
  }

  @Test
  void ensureCatalog_onAFreshTenant_seedsExactGranularScopeSets() {
    UUID tenantId = Uuidv7.generate();
    String slug = "catalog-fresh-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status) VALUES (?, ?,"
            + " '{\"en\":\"Fresh\",\"ar\":\"جديد\"}'::jsonb, 'GOVERNMENT', 'SAAS', 'ACTIVE')",
        tenantId,
        slug);

    TenantContext.set(tenantId, slug);
    try {
      roleCatalogSeeder.ensureCatalog(tenantId);
    } finally {
      TenantContext.clear();
    }

    TenantContext.set(tenantId, slug);
    try {
      assertThat(scopesOf(tenantId, "PLATFORM_ADMIN"))
          .containsExactlyInAnyOrder(
              "issue",
              "verify",
              "consume",
              "revoke",
              "schema:manage",
              "consumer:manage",
              "key:manage",
              "tenant:admin",
              "platform:admin");
      assertThat(scopesOf(tenantId, "TENANT_ADMIN"))
          .containsExactlyInAnyOrder(
              "issue",
              "verify",
              "consume",
              "revoke",
              "schema:manage",
              "consumer:manage",
              "key:manage",
              "tenant:admin");
      assertThat(scopesOf(tenantId, "ISSUER_OPERATOR"))
          .containsExactlyInAnyOrder("issue", "verify", "revoke");
      assertThat(scopesOf(tenantId, "ORG_ADMIN")).containsExactlyInAnyOrder("org:admin");
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void ensureCatalog_calledTwice_isIdempotent_noDuplicateRoleRows() {
    UUID tenantId = Uuidv7.generate();
    String slug = "catalog-idempotent-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status) VALUES"
            + " (?, ?, '{\"en\":\"Idem\",\"ar\":\"معاد\"}'::jsonb, 'GOVERNMENT', 'SAAS', 'ACTIVE')",
        tenantId,
        slug);

    TenantContext.set(tenantId, slug);
    try {
      roleCatalogSeeder.ensureCatalog(tenantId);
      roleCatalogSeeder.ensureCatalog(tenantId);

      Integer roleCount =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM role WHERE tenant_id = ?", Integer.class, tenantId);
      assertThat(roleCount).isEqualTo(4);
    } finally {
      TenantContext.clear();
    }
  }

  private Set<String> scopesOf(UUID tenantId, String roleCode) {
    List<String[]> rows =
        jdbc.query(
            "SELECT scopes FROM role WHERE code = ? AND tenant_id = ?",
            (rs, i) -> toArray(rs.getArray("scopes")),
            roleCode,
            tenantId);
    assertThat(rows).as("catalog role '" + roleCode + "'").hasSize(1);
    return Set.of(rows.get(0));
  }

  private static String[] toArray(Array sqlArray) {
    try {
      return (String[]) sqlArray.getArray();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
