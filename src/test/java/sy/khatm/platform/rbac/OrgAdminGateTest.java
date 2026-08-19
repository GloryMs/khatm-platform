package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.shared.TenantContext;

/**
 * KH-2.6b, spec FS-2.5 §3/§4 — the {@code org:admin} on-behalf-of plane over real HTTP: the
 * scope/direct-child gate (deny-by-default), the privilege ceiling (no credentials, no signing
 * keys), the dual audit trail, aggregated-report numbers, and the mandatory-TOTP wall.
 *
 * <p>Every scenario onboards its own fresh tenants (via the real {@code platform:admin} plane) and
 * links them via {@code POST /api/v1/admin/tenants/{id}/parent} — the exact live topology an
 * org:admin caller acts within, not a shortcut around it.
 */
class OrgAdminGateTest extends RbacHttpTestSupport {

  private static final String ORG_BASE = "/api/v1/org";
  private static final String TENANTS_BASE = "/api/v1/admin/tenants";
  private static final String KEYS_BASE = "/api/v1/admin/api-keys";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private JdbcTemplate jdbc;

  private record Org(UUID id, String slug) {}

  // ── Setup helpers ─────────────────────────────────────────────────────────────────────────

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
   * Creates an ORG_ADMIN user directly in {@code parent} via the platform:admin on-behalf-of plane.
   */
  private String[] createOrgAdminUser(AuthenticatedSession admin, Org parent) {
    String username = "orgadmin-" + UUID.randomUUID().toString().substring(0, 8);
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            TENANTS_BASE + "/" + parent.id() + "/users",
            admin,
            Map.of(
                "username",
                username,
                "displayNameI18n",
                Map.of("en", "Org Admin", "ar", "مدير جهة أم"),
                "roles",
                java.util.List.of("ORG_ADMIN")));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = readTree(created.getBody());
    return new String[] {username, body.get("temporaryPassword").asText()};
  }

  private String[] createTenantAdminUser(AuthenticatedSession admin, Org parent) {
    String username = "tadmin-" + UUID.randomUUID().toString().substring(0, 8);
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            TENANTS_BASE + "/" + parent.id() + "/users",
            admin,
            Map.of(
                "username",
                username,
                "displayNameI18n",
                Map.of("en", "Tenant Admin", "ar", "مدير جهة"),
                "roles",
                java.util.List.of("TENANT_ADMIN")));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = readTree(created.getBody());
    return new String[] {username, body.get("temporaryPassword").asText()};
  }

  /**
   * Logs in a freshly-created temporary-password user, clears the forced-password-change gate, and
   * returns a session with TOTP left <em>unconfirmed</em> — the shape {@link
   * #totp_requiredScope_withNoActiveEnrollment_returns403} needs; every other test calls {@link
   * #bootstrapPrivilegedSession} instead, which goes one step further and confirms TOTP too.
   */
  private AuthenticatedSession clearPasswordChangeOnly(
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
    return first;
  }

  /**
   * Clears the forced-password-change gate, then logs in again so {@link SessionTestSupport}'s own
   * transparent TOTP bootstrap (triggered on a fresh login for a username it hasn't seen succeed
   * before) enrolls and confirms TOTP — every mandatory-2FA scope (spec FS-2.2 V1, now including
   * {@code org:admin}, KH-2.6b) would otherwise wall this session off immediately.
   */
  private AuthenticatedSession bootstrapPrivilegedSession(
      String username, String tempPassword, String tenantSlug) {
    clearPasswordChangeOnly(username, tempPassword, tenantSlug);
    String newPassword = tempPassword + "-changed";
    return SessionTestSupport.login(rest, username, newPassword, tenantSlug);
  }

  private String mintTenantKey(AuthenticatedSession admin, Org tenant, Set<String> scopes) {
    ResponseEntity<String> keyResponse =
        SessionTestSupport.post(
            rest,
            KEYS_BASE,
            admin,
            Map.of("ownerType", "TENANT", "scopes", scopes, "tenantId", tenant.id().toString()));
    assertThat(keyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    return readTree(keyResponse.getBody()).get("rawKey").asText();
  }

  private String issueCredential(String rawKey, String schemaCode) {
    Map<String, Object> body =
        Map.of(
            "schemaCode",
            schemaCode,
            "holderRef",
            "holder-" + UUID.randomUUID(),
            "claims",
            Map.of("field", "value"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/credentials/issue",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return readTree(response.getBody()).get("id").asText();
  }

  private static JsonNode readTree(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      throw new AssertionError("Failed to parse response body: " + body, e);
    }
  }

  private int auditCountForTenant(UUID tenantId, String slug, String action, String entityRef) {
    TenantContext.set(tenantId, slug);
    try {
      return jdbc.queryForObject(
          "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_ref = ?",
          Integer.class,
          action,
          entityRef);
    } finally {
      TenantContext.clear();
    }
  }

  private int auditCountForTenantWithNullEntityRef(UUID tenantId, String slug, String action) {
    TenantContext.set(tenantId, slug);
    try {
      return jdbc.queryForObject(
          "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_ref IS NULL",
          Integer.class,
          action);
    } finally {
      TenantContext.clear();
    }
  }

  // ── Deny-by-default ───────────────────────────────────────────────────────────────────────

  @Test
  void children_withPlainTenantAdmin_returns403() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org parent = onboardTenant(bootstrapAdmin, "org-deny-tadmin");
    String[] tadmin = createTenantAdminUser(bootstrapAdmin, parent);
    AuthenticatedSession session = bootstrapPrivilegedSession(tadmin[0], tadmin[1], parent.slug());

    ResponseEntity<String> response = SessionTestSupport.get(rest, ORG_BASE + "/children", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(readTree(response.getBody()).get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void childUsers_forAGrandchild_returns404() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org parent = onboardTenant(bootstrapAdmin, "org-deny-grandchild-parent");
    Org child = onboardTenant(bootstrapAdmin, "org-deny-grandchild-child");
    linkParent(bootstrapAdmin, child, parent.slug());
    Org grandchild = onboardTenant(bootstrapAdmin, "org-deny-grandchild-gc");
    linkParent(bootstrapAdmin, grandchild, child.slug());
    String[] orgAdmin = createOrgAdminUser(bootstrapAdmin, parent);
    AuthenticatedSession session =
        bootstrapPrivilegedSession(orgAdmin[0], orgAdmin[1], parent.slug());

    ResponseEntity<String> response =
        SessionTestSupport.get(rest, ORG_BASE + "/children/" + grandchild.id() + "/users", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(readTree(response.getBody()).get("code").asText()).isEqualTo("KH-ORG-0404");
  }

  @Test
  void childUsers_forAnotherOrgAdminsChild_returns404() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org parentA = onboardTenant(bootstrapAdmin, "org-deny-cross-a");
    Org childA = onboardTenant(bootstrapAdmin, "org-deny-cross-a-child");
    linkParent(bootstrapAdmin, childA, parentA.slug());
    Org parentB = onboardTenant(bootstrapAdmin, "org-deny-cross-b");
    String[] orgAdminB = createOrgAdminUser(bootstrapAdmin, parentB);
    AuthenticatedSession sessionB =
        bootstrapPrivilegedSession(orgAdminB[0], orgAdminB[1], parentB.slug());

    ResponseEntity<String> response =
        SessionTestSupport.get(rest, ORG_BASE + "/children/" + childA.id() + "/users", sessionB);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(readTree(response.getBody()).get("code").asText()).isEqualTo("KH-ORG-0404");
  }

  // ── Privilege ceiling ─────────────────────────────────────────────────────────────────────

  @Test
  void signingKeys_withOnlyOrgAdminScope_returns403() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org parent = onboardTenant(bootstrapAdmin, "org-priv-keys");
    String[] orgAdmin = createOrgAdminUser(bootstrapAdmin, parent);
    AuthenticatedSession session =
        bootstrapPrivilegedSession(orgAdmin[0], orgAdmin[1], parent.slug());

    ResponseEntity<String> response =
        SessionTestSupport.get(rest, "/api/v1/admin/signing-keys", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(readTree(response.getBody()).get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void childCredential_isNotReadableByOrgAdmin_evenByDirectId() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org parent = onboardTenant(bootstrapAdmin, "org-priv-cred-parent");
    Org child = onboardTenant(bootstrapAdmin, "org-priv-cred-child");
    linkParent(bootstrapAdmin, child, parent.slug());
    String childKey = mintTenantKey(bootstrapAdmin, child, Set.of("issue"));
    String credentialId = issueCredential(childKey, "OrgPrivCred/v1");
    String[] orgAdmin = createOrgAdminUser(bootstrapAdmin, parent);
    AuthenticatedSession session =
        bootstrapPrivilegedSession(orgAdmin[0], orgAdmin[1], parent.slug());

    ResponseEntity<String> response =
        SessionTestSupport.get(rest, "/api/v1/credentials/" + credentialId, session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ── Dual audit ────────────────────────────────────────────────────────────────────────────

  @Test
  void suspendChild_writesOrgOnBehalfOf_inParent_andTenantSuspended_inChild() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org parent = onboardTenant(bootstrapAdmin, "org-audit-parent");
    Org child = onboardTenant(bootstrapAdmin, "org-audit-child");
    linkParent(bootstrapAdmin, child, parent.slug());
    String[] orgAdmin = createOrgAdminUser(bootstrapAdmin, parent);
    AuthenticatedSession session =
        bootstrapPrivilegedSession(orgAdmin[0], orgAdmin[1], parent.slug());

    ResponseEntity<String> suspended =
        SessionTestSupport.post(
            rest, ORG_BASE + "/children/" + child.id() + "/suspend", session, null);
    assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(auditCountForTenant(parent.id(), parent.slug(), "ORG_ON_BEHALF_OF", child.slug()))
        .as("parent-side marker")
        .isGreaterThan(0);
    assertThat(auditCountForTenant(child.id(), child.slug(), "TENANT_SUSPENDED", child.slug()))
        .as("child-side specific action")
        .isGreaterThan(0);
  }

  @Test
  void createChildUser_writesOrgOnBehalfOf_inParent_andUserCreated_inChild() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org parent = onboardTenant(bootstrapAdmin, "org-audit-user-parent");
    Org child = onboardTenant(bootstrapAdmin, "org-audit-user-child");
    linkParent(bootstrapAdmin, child, parent.slug());
    String[] orgAdmin = createOrgAdminUser(bootstrapAdmin, parent);
    AuthenticatedSession session =
        bootstrapPrivilegedSession(orgAdmin[0], orgAdmin[1], parent.slug());
    String newUsername = "childuser-" + UUID.randomUUID().toString().substring(0, 8);

    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            ORG_BASE + "/children/" + child.id() + "/users",
            session,
            Map.of(
                "username",
                newUsername,
                "displayNameI18n",
                Map.of("en", "Child User", "ar", "مستخدم ابن"),
                "roles",
                java.util.List.of("ISSUER_OPERATOR")));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(auditCountForTenant(parent.id(), parent.slug(), "ORG_ON_BEHALF_OF", child.slug()))
        .as("parent-side marker")
        .isGreaterThan(0);
    assertThat(auditCountForTenant(child.id(), child.slug(), "USER_CREATED", newUsername))
        .as("child-side specific action")
        .isGreaterThan(0);
  }

  // ── Aggregated report numbers ─────────────────────────────────────────────────────────────

  @Test
  void reports_perChildCountersAndRollup_matchIssuedCredentials() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org parent = onboardTenant(bootstrapAdmin, "org-report-parent");
    Org childOne = onboardTenant(bootstrapAdmin, "org-report-child-one");
    linkParent(bootstrapAdmin, childOne, parent.slug());
    Org childTwo = onboardTenant(bootstrapAdmin, "org-report-child-two");
    linkParent(bootstrapAdmin, childTwo, parent.slug());
    String keyOne = mintTenantKey(bootstrapAdmin, childOne, Set.of("issue"));
    String keyTwo = mintTenantKey(bootstrapAdmin, childTwo, Set.of("issue"));
    for (int i = 0; i < 3; i++) {
      issueCredential(keyOne, "OrgReportChildOne/v1");
    }
    for (int i = 0; i < 2; i++) {
      issueCredential(keyTwo, "OrgReportChildTwo/v1");
    }
    String[] orgAdmin = createOrgAdminUser(bootstrapAdmin, parent);
    AuthenticatedSession session =
        bootstrapPrivilegedSession(orgAdmin[0], orgAdmin[1], parent.slug());

    ResponseEntity<String> response = SessionTestSupport.get(rest, ORG_BASE + "/reports", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = readTree(response.getBody());
    long issuedOne = issuedFor(body, childOne.slug());
    long issuedTwo = issuedFor(body, childTwo.slug());
    assertThat(issuedOne).isEqualTo(3);
    assertThat(issuedTwo).isEqualTo(2);
    assertThat(body.get("rollup").get("issued").asLong()).isEqualTo(5);

    assertThat(
            auditCountForTenantWithNullEntityRef(parent.id(), parent.slug(), "ORG_REPORT_VIEWED"))
        .as("report call is audited under the parent's own trail")
        .isGreaterThan(0);
  }

  private static long issuedFor(JsonNode reportBody, String slug) {
    for (JsonNode entry : reportBody.get("children")) {
      if (slug.equals(entry.get("tenantSlug").asText())) {
        return entry.get("counters").get("issued").asLong();
      }
    }
    throw new AssertionError("No report entry for tenant " + slug);
  }

  // ── TOTP wall ─────────────────────────────────────────────────────────────────────────────

  @Test
  void children_orgAdminWithNoActiveTotp_returns403() {
    AuthenticatedSession bootstrapAdmin = loginAsBootstrapAdmin();
    Org parent = onboardTenant(bootstrapAdmin, "org-totp");
    String[] orgAdmin = createOrgAdminUser(bootstrapAdmin, parent);
    // Clears the forced-password-change gate only — TOTP is deliberately left unconfirmed.
    AuthenticatedSession session = clearPasswordChangeOnly(orgAdmin[0], orgAdmin[1], parent.slug());

    ResponseEntity<String> response = SessionTestSupport.get(rest, ORG_BASE + "/children", session);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(readTree(response.getBody()).get("code").asText()).isEqualTo("KH-USR-1403");
  }
}
