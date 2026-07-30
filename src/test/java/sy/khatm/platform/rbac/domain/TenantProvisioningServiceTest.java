package sy.khatm.platform.rbac.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.support.IntegrationTestSupport;
import sy.khatm.platform.tenant.api.TenantAdmin;

/**
 * Spec FS-2.2 D6 — the rbac-side onboarding orchestration: a fresh onboard with {@code
 * initialAdmin} seeds the role catalog and creates the first {@code TENANT_ADMIN} with a one-time
 * temporary password in one call; a retried onboard against the same slug (resume, spec V3) fills
 * only what is missing and never duplicates the admin. {@link OnBehalfOfExecutor#runAsTenant}
 * requires a live {@code platform:admin} principal AND an already-set caller-tenant {@link
 * TenantContext} (it writes its pre-switch {@code ON_BEHALF_OF} audit row under the caller's own
 * tenant, then clears the {@link ThreadLocal} entirely in its own {@code finally} — it does not
 * restore whatever was ambient before it ran), so every call into the orchestration here
 * re-establishes both via {@link #asPlatformAdmin}.
 */
class TenantProvisioningServiceTest extends IntegrationTestSupport {

  @Autowired private TenantProvisioningService provisioning;
  @Autowired private TenantAdmin tenantAdmin;
  @Autowired private JdbcTemplate jdbc;

  private static String uniqueSlug(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  /**
   * Runs {@code action} with a live {@code platform:admin} principal and {@link TenantContext} set
   * to the default tenant — the exact ambient state a real HTTP request already has by the time it
   * reaches this service (spec FS-2.2 D4's {@code rbac.security.TenantContextFilter} always sets
   * this before any controller runs). Clears both afterward, in a {@code finally}, regardless of
   * what {@code action} itself left set — {@link OnBehalfOfExecutor#runAsTenant} clears the {@link
   * TenantContext} {@link ThreadLocal} entirely rather than restoring a prior value, so this must
   * be called again before every subsequent orchestration call on the same thread, not just once
   * per test method.
   */
  private static <T> T asPlatformAdmin(Supplier<T> action) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(
                "test-platform-admin",
                "n/a",
                List.of(new SimpleGrantedAuthority("SCOPE_platform:admin"))));
    TenantContext.set(TenantContext.DEFAULT_TENANT_ID, TenantContext.DEFAULT_TENANT_SLUG);
    try {
      return action.get();
    } finally {
      TenantContext.clear();
      SecurityContextHolder.clearContext();
    }
  }

  /** Runs an RLS-scoped read against {@code tenantId}'s own context, then clears it. */
  private <T> T asTenant(UUID tenantId, String slug, Supplier<T> query) {
    TenantContext.set(tenantId, slug);
    try {
      return query.get();
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void onboard_withInitialAdmin_seedsRoleCatalog_andCreatesFirstTenantAdmin() {
    String slug = uniqueSlug("onboard-fresh");
    String adminUsername = "admin-" + UUID.randomUUID().toString().substring(0, 8);

    OnboardTenantResult result =
        asPlatformAdmin(
            () ->
                provisioning.onboard(
                    slug,
                    new LocalizedText("Acme", "أكمي"),
                    "GOVERNMENT",
                    null,
                    adminUsername,
                    new LocalizedText("First Admin", "أول مدير")));

    assertThat(result.tenant().slug()).isEqualTo(slug);
    assertThat(result.initialAdminUsername()).isEqualTo(adminUsername);
    assertThat(result.initialAdminTemporaryPassword()).isNotBlank();

    asTenant(
        result.tenant().id(),
        slug,
        () -> {
          Integer roleCount =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM role WHERE tenant_id = ?",
                  Integer.class,
                  result.tenant().id());
          assertThat(roleCount).as("the full 3-role catalog must exist").isEqualTo(3);

          Integer userCount =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM app_user WHERE tenant_id = ? AND username = ?",
                  Integer.class,
                  result.tenant().id(),
                  adminUsername);
          assertThat(userCount).isEqualTo(1);

          String assignedRole =
              jdbc.queryForObject(
                  "SELECT r.code FROM role r JOIN user_role ur ON ur.role_id = r.id "
                      + "JOIN app_user au ON au.id = ur.user_id WHERE au.username = ?",
                  String.class,
                  adminUsername);
          assertThat(assignedRole).isEqualTo("TENANT_ADMIN");
          return null;
        });
  }

  @Test
  void onboard_withNoInitialAdmin_stillSeedsCatalog_returnsNullAdminFields() {
    String slug = uniqueSlug("onboard-nocatalog");

    OnboardTenantResult result =
        asPlatformAdmin(
            () ->
                provisioning.onboard(
                    slug, new LocalizedText("Acme", "أكمي"), "GOVERNMENT", null, null, null));

    assertThat(result.initialAdminUsername()).isNull();
    assertThat(result.initialAdminTemporaryPassword()).isNull();

    asTenant(
        result.tenant().id(),
        slug,
        () -> {
          Integer roleCount =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM role WHERE tenant_id = ?",
                  Integer.class,
                  result.tenant().id());
          assertThat(roleCount).isEqualTo(3);
          return null;
        });
  }

  @Test
  void onboard_retriedForAHalfOnboardedTenant_fillsCatalogAndAdmin_withoutDuplicating() {
    String slug = uniqueSlug("onboard-resume");
    String adminUsername = "resumed-admin-" + UUID.randomUUID().toString().substring(0, 8);

    // Simulate a prior onboarding that died right after tenantAdmin.create() — before the rbac
    // orchestration ever ran (no role catalog, no admin yet), the exact shape a resumed call must
    // repair. TenantAdmin#create is called service-level here (no HTTP), matching
    // tenant.domain.TenantAdminServiceTest's own established no-SecurityContext convention.
    var firstTenant =
        tenantAdmin.create(slug, new LocalizedText("Acme", "أكمي"), "GOVERNMENT", null);
    Integer preResumeRoleCount =
        asTenant(
            firstTenant.id(),
            slug,
            () ->
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM role WHERE tenant_id = ?",
                    Integer.class,
                    firstTenant.id()));
    assertThat(preResumeRoleCount)
        .as("the simulated partial onboarding has no catalog yet")
        .isZero();

    OnboardTenantResult resumed =
        asPlatformAdmin(
            () ->
                provisioning.onboard(
                    slug,
                    new LocalizedText("Acme", "أكمي"),
                    "GOVERNMENT",
                    null,
                    adminUsername,
                    new LocalizedText("Resumed Admin", "مدير مستأنف")));

    assertThat(resumed.tenant().id()).isEqualTo(firstTenant.id());
    assertThat(resumed.initialAdminUsername()).isEqualTo(adminUsername);
    assertThat(resumed.initialAdminTemporaryPassword()).isNotBlank();

    asTenant(
        firstTenant.id(),
        slug,
        () -> {
          Integer roleCount =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM role WHERE tenant_id = ?", Integer.class, firstTenant.id());
          assertThat(roleCount).isEqualTo(3);
          return null;
        });

    // Retrying AGAIN must not duplicate the admin or re-issue a fresh password.
    OnboardTenantResult secondRetry =
        asPlatformAdmin(
            () ->
                provisioning.onboard(
                    slug,
                    new LocalizedText("Acme", "أكمي"),
                    "GOVERNMENT",
                    null,
                    adminUsername,
                    new LocalizedText("Resumed Admin", "مدير مستأنف")));
    assertThat(secondRetry.initialAdminUsername()).isEqualTo(adminUsername);
    assertThat(secondRetry.initialAdminTemporaryPassword())
        .as("an already-existing admin's password is never re-displayed")
        .isNull();

    asTenant(
        firstTenant.id(),
        slug,
        () -> {
          Integer userCount =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM app_user WHERE tenant_id = ? AND username = ?",
                  Integer.class,
                  firstTenant.id(),
                  adminUsername);
          assertThat(userCount)
              .as("the admin must exist exactly once, never duplicated")
              .isEqualTo(1);
          return null;
        });
  }

  @Test
  void createUserInTenant_createsUserInTheNamedTenant_notTheCallersOwn() {
    String slug = uniqueSlug("cross-tenant-user");
    var tenant =
        asPlatformAdmin(
            () ->
                provisioning
                    .onboard(
                        slug, new LocalizedText("Acme", "أكمي"), "GOVERNMENT", null, null, null)
                    .tenant());
    String username = "cross-" + UUID.randomUUID().toString().substring(0, 8);

    CreatedUser created =
        asPlatformAdmin(
            () ->
                provisioning.createUserInTenant(
                    tenant.id(),
                    username,
                    new LocalizedText("Cross", "عابر"),
                    Set.of("ISSUER_OPERATOR")));

    assertThat(created.temporaryPassword()).isNotBlank();
    asTenant(
        tenant.id(),
        slug,
        () -> {
          Integer userCount =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM app_user WHERE tenant_id = ? AND username = ?",
                  Integer.class,
                  tenant.id(),
                  username);
          assertThat(userCount).isEqualTo(1);
          return null;
        });

    // ON_BEHALF_OF is recorded under the CALLER's own ambient tenant (the default tenant here),
    // not the target — read it back under that context.
    Integer onBehalfOfAuditRows =
        asTenant(
            TenantContext.DEFAULT_TENANT_ID,
            TenantContext.DEFAULT_TENANT_SLUG,
            () ->
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'ON_BEHALF_OF' AND entity_ref"
                        + " = ?",
                    Integer.class,
                    slug));
    assertThat(onBehalfOfAuditRows).as("cross-tenant action must be audited").isGreaterThan(0);
  }

  @Test
  void listUsersInTenant_returnsTheNamedTenantsUsers_notTheCallersOwn() {
    String slug = uniqueSlug("cross-tenant-list");
    var tenant =
        asPlatformAdmin(
            () ->
                provisioning
                    .onboard(
                        slug, new LocalizedText("Acme", "أكمي"), "GOVERNMENT", null, null, null)
                    .tenant());
    String username = "list-" + UUID.randomUUID().toString().substring(0, 8);
    asPlatformAdmin(
        () ->
            provisioning.createUserInTenant(
                tenant.id(),
                username,
                new LocalizedText("Cross", "عابر"),
                Set.of("ISSUER_OPERATOR")));

    List<UserSummary> users = asPlatformAdmin(() -> provisioning.listUsersInTenant(tenant.id()));

    assertThat(users).extracting(UserSummary::username).containsExactly(username);
  }
}
