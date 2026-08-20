package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.shared.TenantContext;

/**
 * chore/role-grant-ceiling — {@code UserAdminService#create}/{@code #replaceRoles}'s role-grant
 * ceiling gate, over real HTTP: a plain {@code tenant:admin} can still grant an ordinary role (no
 * regression), cannot grant {@code PLATFORM_ADMIN}/{@code ORG_ADMIN} on either the local or the
 * {@code org:admin}-mediated path, and a real {@code platform:admin} can still grant anything.
 * Every rejection is proven audited ({@code ROLE_GRANT_REJECTED}), not just rejected on the wire.
 */
class RoleGrantCeilingHttpTest extends RbacHttpTestSupport {

  private static final String USERS_BASE = "/api/v1/users";
  private static final String ORG_BASE = "/api/v1/org";
  private static final String TENANTS_BASE = "/api/v1/admin/tenants";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private JdbcTemplate jdbc;

  private record Org(UUID id, String slug) {}

  private AuthenticatedSession loginAsBootstrapAdmin() {
    return SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
  }

  private Org onboardTenant(AuthenticatedSession admin, String prefix) {
    String slug = prefix + "-" + UUID.randomUUID();
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            TENANTS_BASE,
            admin,
            Map.of("slug", slug, "nameI18n", Map.of("en", "x", "ar", "x"), "type", "OTHER"));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    return new Org(UUID.fromString(readTree(created.getBody()).get("id").asText()), slug);
  }

  private void linkParent(AuthenticatedSession admin, Org child, String parentSlug) {
    ResponseEntity<String> linked =
        SessionTestSupport.post(
            rest,
            TENANTS_BASE + "/" + child.id() + "/parent",
            admin,
            Map.of("parentSlug", parentSlug));
    assertThat(linked.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  /**
   * Creates a user with {@code roleCode} directly in {@code tenant}, via the platform:admin plane.
   */
  private String[] createUserWithRole(AuthenticatedSession admin, Org tenant, String roleCode) {
    String username =
        "u-" + roleCode.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            TENANTS_BASE + "/" + tenant.id() + "/users",
            admin,
            Map.of(
                "username",
                username,
                "displayNameI18n",
                Map.of("en", "Test User", "ar", "مستخدم اختبار"),
                "roles",
                List.of(roleCode)));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = readTree(created.getBody());
    return new String[] {username, body.get("temporaryPassword").asText()};
  }

  private AuthenticatedSession bootstrapPrivilegedSession(
      String username, String tempPassword, String tenantSlug) {
    AuthenticatedSession first = SessionTestSupport.login(rest, username, tempPassword, tenantSlug);
    String newPassword = tempPassword + "-changed";
    ResponseEntity<String> changed =
        SessionTestSupport.post(
            rest,
            "/api/v1/users/me/password",
            first,
            Map.of("currentPassword", tempPassword, "newPassword", newPassword));
    assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
    return SessionTestSupport.login(rest, username, newPassword, tenantSlug);
  }

  private static JsonNode readTree(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      throw new AssertionError("Failed to parse response body: " + body, e);
    }
  }

  private int roleGrantRejectedAuditCount(UUID tenantId, String slug, String entityRef) {
    TenantContext.set(tenantId, slug);
    try {
      return jdbc.queryForObject(
          "SELECT COUNT(*) FROM audit_log WHERE action = 'ROLE_GRANT_REJECTED' AND entity_ref = ?",
          Integer.class,
          entityRef);
    } finally {
      TenantContext.clear();
    }
  }

  // ── D3.1 — no regression for an ordinary role grant ──────────────────────────────────────

  @Test
  void tenantAdmin_grantsOrdinaryRole_succeeds() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org tenant = onboardTenant(bootstrapAdmin, "ceiling-ok-tadmin");
    String[] tadmin = createUserWithRole(bootstrapAdmin, tenant, "TENANT_ADMIN");
    AuthenticatedSession session = bootstrapPrivilegedSession(tadmin[0], tadmin[1], tenant.slug());
    String newUsername = "issuer-" + UUID.randomUUID().toString().substring(0, 8);

    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            USERS_BASE,
            session,
            Map.of(
                "username",
                newUsername,
                "displayNameI18n",
                Map.of("en", "Issuer Op", "ar", "مشغّل"),
                "roles",
                List.of("ISSUER_OPERATOR")));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  // ── D3.2 — tenant:admin cannot grant PLATFORM_ADMIN (create) or ORG_ADMIN (replaceRoles) ──

  @Test
  void tenantAdmin_createWithPlatformAdminRole_rejected_andAudited() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org tenant = onboardTenant(bootstrapAdmin, "ceiling-deny-create");
    String[] tadmin = createUserWithRole(bootstrapAdmin, tenant, "TENANT_ADMIN");
    AuthenticatedSession session = bootstrapPrivilegedSession(tadmin[0], tadmin[1], tenant.slug());
    String newUsername = "wannabe-platform-" + UUID.randomUUID().toString().substring(0, 8);

    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            USERS_BASE,
            session,
            Map.of(
                "username",
                newUsername,
                "displayNameI18n",
                Map.of("en", "Escalator", "ar", "مصعِّد"),
                "roles",
                List.of("PLATFORM_ADMIN")));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(readTree(created.getBody()).get("code").asText()).isEqualTo("KH-USR-2403");
    assertThat(roleGrantRejectedAuditCount(tenant.id(), tenant.slug(), newUsername))
        .isGreaterThan(0);
  }

  @Test
  void tenantAdmin_replaceRolesWithOrgAdminRole_rejected_andAudited() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org tenant = onboardTenant(bootstrapAdmin, "ceiling-deny-replace");
    String[] tadmin = createUserWithRole(bootstrapAdmin, tenant, "TENANT_ADMIN");
    AuthenticatedSession session = bootstrapPrivilegedSession(tadmin[0], tadmin[1], tenant.slug());
    String[] target = createUserWithRole(bootstrapAdmin, tenant, "ISSUER_OPERATOR");
    // Resolve the target's id from the list endpoint by username rather than assuming position.
    JsonNode users = readTree(SessionTestSupport.get(rest, USERS_BASE, session).getBody());
    UUID resolvedId = null;
    for (JsonNode u : users) {
      if (target[0].equals(u.get("username").asText())) {
        resolvedId = UUID.fromString(u.get("id").asText());
      }
    }
    assertThat(resolvedId).as("target user must be resolvable from the list").isNotNull();

    ResponseEntity<String> replaced =
        SessionTestSupport.post(
            rest,
            USERS_BASE + "/" + resolvedId + "/roles",
            session,
            Map.of("roles", List.of("ORG_ADMIN")));

    assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(readTree(replaced.getBody()).get("code").asText()).isEqualTo("KH-USR-2403");
    assertThat(roleGrantRejectedAuditCount(tenant.id(), tenant.slug(), target[0])).isGreaterThan(0);
  }

  // ── D3.3 — org:admin via delegation cannot grant ORG_ADMIN to a child (no self-propagation) ─

  @Test
  void orgAdmin_createsChildUserWithOrgAdminRole_rejected_evaluatedOnRealCaller() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org parent = onboardTenant(bootstrapAdmin, "ceiling-deny-org-parent");
    Org child = onboardTenant(bootstrapAdmin, "ceiling-deny-org-child");
    linkParent(bootstrapAdmin, child, parent.slug());
    String[] orgAdmin = createUserWithRole(bootstrapAdmin, parent, "ORG_ADMIN");
    AuthenticatedSession session =
        bootstrapPrivilegedSession(orgAdmin[0], orgAdmin[1], parent.slug());
    String newUsername = "wannabe-org-" + UUID.randomUUID().toString().substring(0, 8);

    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            ORG_BASE + "/children/" + child.id() + "/users",
            session,
            Map.of(
                "username",
                newUsername,
                "displayNameI18n",
                Map.of("en", "Self Propagator", "ar", "تسلسل ذاتي"),
                "roles",
                List.of("ORG_ADMIN")));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(readTree(created.getBody()).get("code").asText()).isEqualTo("KH-USR-2403");
    // Rejection lands under the CHILD's own trail — UserAdminService runs post-context-switch,
    // exactly like every other org-mediated write (spec FS-2.5's dual-audit shape).
    assertThat(roleGrantRejectedAuditCount(child.id(), child.slug(), newUsername)).isGreaterThan(0);
  }

  // ── D3.4 — platform:admin grants anything, including PLATFORM_ADMIN itself ─────────────────

  @Test
  void platformAdmin_grantsPlatformAdminRole_succeeds() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org tenant = onboardTenant(bootstrapAdmin, "ceiling-platform-ok");

    String[] created = createUserWithRole(bootstrapAdmin, tenant, "PLATFORM_ADMIN");

    assertThat(created[0]).isNotBlank();
  }
}
