package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.shared.TenantContext;

/**
 * KH-2.8.1 (spec FS-2.7a D10) — a parent organisation manages its DIRECT children's issuer clients
 * through {@code /api/v1/org/children/{id}/issuer-clients}: it works for a direct child, every
 * other target (a grandchild, an unrelated tenant, a made-up id) is the unified {@code
 * KH-ORG-0404}, the child's own key works only inside the child, and both audit trails carry their
 * half (parent-side marker, child-side lifecycle row).
 */
class IssuerClientOrgPlaneHttpTest extends IssuerClientHttpTestSupport {

  private static final String ORG = "/api/v1/org/children/";

  @Test
  void parentManagesADirectChildsClients_andEveryOtherTargetIsTheUnified404() {
    AuthenticatedSession platform = adminSession();
    Org parent = onboardTenant(platform, "oc-parent");
    Org child = onboardTenant(platform, "oc-child");
    Org grandchild = onboardTenant(platform, "oc-grand");
    Org stranger = onboardTenant(platform, "oc-stranger");
    link(platform, child, parent.slug());
    link(platform, grandchild, child.slug());
    String childSchema = publishedSchemaIn(child.id(), child.slug(), "oc-schema");
    AuthenticatedSession orgAdmin = orgAdminOf(platform, parent);

    // Create in the child.
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            ORG + child.id() + "/issuer-clients",
            orgAdmin,
            Map.of("name", nameBody(), "allowedSchemaIds", List.of(childSchema)));
    assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
    ClientKey childClient = readCreated(created.getBody());
    assertThat(childClient.holderSecret()).as("a child never generates a holder secret").isNull();
    assertThat(tree(created.getBody()).get("client").get("tenantId").asText())
        .isEqualTo(child.id().toString());

    // List shows it; the key really belongs to the child, not the parent.
    ResponseEntity<String> list =
        SessionTestSupport.get(rest, ORG + child.id() + "/issuer-clients", orgAdmin);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getBody()).contains(childClient.prefix()).doesNotContain(childClient.apiKey());

    ResponseEntity<String> issued =
        m2mIssue(childClient.apiKey(), issueBody(childSchema, validHolderRef()));
    assertThat(issued.getStatusCode()).as(issued.getBody()).isEqualTo(HttpStatus.OK);
    TenantContext.set(child.id(), child.slug());
    try {
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential", Integer.class))
          .isEqualTo(1);
    } finally {
      TenantContext.clear();
    }

    // Lifecycle through the org plane.
    String clientBase = ORG + child.id() + "/issuer-clients/" + childClient.id();
    assertThat(SessionTestSupport.post(rest, clientBase + "/suspend", orgAdmin, Map.of()).getBody())
        .contains("\"status\":\"SUSPENDED\"");
    assertThat(SessionTestSupport.post(rest, clientBase + "/resume", orgAdmin, Map.of()).getBody())
        .contains("\"status\":\"ACTIVE\"");
    ResponseEntity<String> rotated =
        SessionTestSupport.post(rest, clientBase + "/rotate", orgAdmin, Map.of());
    assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            SessionTestSupport.post(
                    rest,
                    ORG
                        + child.id()
                        + "/issuer-clients/"
                        + readCreated(rotated.getBody()).id()
                        + "/revoke",
                    orgAdmin,
                    Map.of())
                .getBody())
        .contains("\"status\":\"REVOKED\"");

    // Dual audit: parent-side marker in the PARENT's trail, lifecycle rows in the CHILD's.
    assertThat(auditCount(parent, "ORG_ON_BEHALF_OF", child.slug())).isGreaterThanOrEqualTo(1);
    assertThat(auditCount(child, "ISSUER_CLIENT_CREATED", childClient.prefix())).isEqualTo(1);
    assertThat(auditCount(parent, "ISSUER_CLIENT_CREATED", childClient.prefix()))
        .as("the lifecycle row is NOT in the parent's trail")
        .isZero();

    // Grandchild, unrelated tenant, unknown id — all the same unified 404.
    for (String target :
        List.of(
            grandchild.id().toString(), stranger.id().toString(), UUID.randomUUID().toString())) {
      for (ResponseEntity<String> denied :
          List.of(
              SessionTestSupport.get(rest, ORG + target + "/issuer-clients", orgAdmin),
              SessionTestSupport.post(
                  rest,
                  ORG + target + "/issuer-clients",
                  orgAdmin,
                  Map.of("name", nameBody(), "allowedSchemaIds", List.of())),
              SessionTestSupport.post(
                  rest,
                  ORG + target + "/issuer-clients/" + childClient.id() + "/revoke",
                  orgAdmin,
                  Map.of()))) {
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tree(denied.getBody()).get("code").asText()).isEqualTo("KH-ORG-0404");
      }
    }
  }

  @Test
  void aChildsClientIdCannotBeReachedThroughAnotherChildsPath() {
    AuthenticatedSession platform = adminSession();
    Org parent = onboardTenant(platform, "oc2-parent");
    Org childA = onboardTenant(platform, "oc2-a");
    Org childB = onboardTenant(platform, "oc2-b");
    link(platform, childA, parent.slug());
    link(platform, childB, parent.slug());
    AuthenticatedSession orgAdmin = orgAdminOf(platform, parent);

    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            ORG + childA.id() + "/issuer-clients",
            orgAdmin,
            Map.of("name", nameBody(), "allowedSchemaIds", List.of()));
    ClientKey inA = readCreated(created.getBody());

    ResponseEntity<String> viaB =
        SessionTestSupport.post(
            rest,
            ORG + childB.id() + "/issuer-clients/" + inA.id() + "/revoke",
            orgAdmin,
            Map.of());
    assertThat(viaB.getStatusCode())
        .as("RLS scopes the lookup to childB, where A's client does not exist")
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(tree(viaB.getBody()).get("code").asText()).isEqualTo("KH-ICL-0404");
  }

  @Test
  void aNonOrgAdminSession_isDeniedTheOrgPlane() {
    AuthenticatedSession platform = adminSession();
    Org parent = onboardTenant(platform, "oc3-parent");
    Org child = onboardTenant(platform, "oc3-child");
    link(platform, child, parent.slug());
    ResponseEntity<String> anonymous =
        rest.exchange(ORG + child.id() + "/issuer-clients", HttpMethod.GET, null, String.class);
    assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────

  private void link(AuthenticatedSession platform, Org child, String parentSlug) {
    ResponseEntity<String> linked =
        SessionTestSupport.post(
            rest,
            "/api/v1/admin/tenants/" + child.id() + "/parent",
            platform,
            Map.of("parentSlug", parentSlug));
    assertThat(linked.getStatusCode()).as(linked.getBody()).isEqualTo(HttpStatus.OK);
  }

  /** An ORG_ADMIN user of {@code parent}, password-changed and TOTP-enrolled, logged in. */
  private AuthenticatedSession orgAdminOf(AuthenticatedSession platform, Org parent) {
    String username = "orgadmin-" + UUID.randomUUID().toString().substring(0, 8);
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            "/api/v1/admin/tenants/" + parent.id() + "/users",
            platform,
            Map.of(
                "username",
                username,
                "displayNameI18n",
                Map.of("en", "Org Admin", "ar", "مدير جهة أم"),
                "roles",
                List.of("ORG_ADMIN")));
    assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.OK);
    JsonNode body = tree(created.getBody());
    String temp = body.get("temporaryPassword").asText();
    AuthenticatedSession first = SessionTestSupport.login(rest, username, temp, parent.slug());
    String changed = temp + "-changed";
    ResponseEntity<String> password =
        SessionTestSupport.post(
            rest,
            "/api/v1/users/me/password",
            first,
            Map.of("currentPassword", temp, "newPassword", changed));
    assertThat(password.getStatusCode()).isEqualTo(HttpStatus.OK);
    return SessionTestSupport.login(rest, username, changed, parent.slug());
  }

  private int auditCount(Org tenant, String action, String entityRef) {
    TenantContext.set(tenant.id(), tenant.slug());
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
}
