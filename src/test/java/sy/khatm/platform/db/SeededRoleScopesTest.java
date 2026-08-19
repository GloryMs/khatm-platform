package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * V10__scope_registry_rescope.sql (KH-2.2a, spec FS-2.2 D3) — every seeded role's {@code scopes}
 * matches the migration's own target sets exactly, and the retired {@code admin} scope is gone from
 * every role, platform-wide (not just the default tenant's three seeds — the migration's {@code
 * WHERE code = ...} clauses are tenant-agnostic by construction).
 */
class SeededRoleScopesTest extends IntegrationTestSupport {

  @Autowired private JdbcTemplate jdbc;

  @Test
  void platformAdmin_hasAllNineScopes() {
    assertThat(scopesOf("PLATFORM_ADMIN"))
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
  }

  @Test
  void tenantAdmin_hasEveryScopeExceptPlatformAdmin() {
    assertThat(scopesOf("TENANT_ADMIN"))
        .containsExactlyInAnyOrder(
            "issue",
            "verify",
            "consume",
            "revoke",
            "schema:manage",
            "consumer:manage",
            "key:manage",
            "tenant:admin");
  }

  @Test
  void issuerOperator_hasOnlyIssueVerifyRevoke() {
    assertThat(scopesOf("ISSUER_OPERATOR")).containsExactlyInAnyOrder("issue", "verify", "revoke");
  }

  @Test
  void orgAdmin_hasOnlyOrgAdmin() {
    assertThat(scopesOf("ORG_ADMIN")).containsExactlyInAnyOrder("org:admin");
  }

  @Test
  void noRoleAnywhere_stillCarriesTheRetiredAdminScope() {
    List<String> codes =
        jdbc.query(
            "SELECT code FROM role WHERE 'admin' = ANY(scopes)", (rs, i) -> rs.getString("code"));

    assertThat(codes).as("roles still carrying the retired 'admin' scope").isEmpty();
  }

  private Set<String> scopesOf(String roleCode) {
    List<String[]> rows =
        jdbc.query(
            "SELECT scopes FROM role WHERE code = ? AND tenant_id ="
                + " '00000000-0000-0000-0000-000000000001'",
            (rs, i) -> toArray(rs.getArray("scopes")),
            roleCode);
    assertThat(rows).as("seeded role '" + roleCode + "'").hasSize(1);
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
