package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.domain.CreatedUser;
import sy.khatm.platform.rbac.domain.RoleCatalogSeeder;
import sy.khatm.platform.rbac.domain.UserAdminService;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-2.2 D5/D8 — the last-tenant-admin guard, race-proofed: two concurrent operations against
 * the tenant's final two active administrators must produce exactly one success and one {@code
 * KH-USR-0423} rejection, never two successes (which would leave the tenant with zero active
 * admins) and never two rejections (an unnecessary false conflict). Joins the race-test family
 * ({@code ConcurrentConsumeTest}'s harness style) — a per-tenant Postgres advisory transaction lock
 * ({@code UserAdminService#lockTenantForUserMutation}) serializes the count-then-act guard so the
 * second caller always observes the first's already-committed status change.
 */
class ConcurrentLastAdminTest extends IntegrationTestSupport {

  @Autowired private UserAdminService userAdmin;
  @Autowired private RoleCatalogSeeder roleCatalogSeeder;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void lock_twoConcurrentCallsAgainstTheFinalTwoAdmins_exactlyOneSucceeds() throws Exception {
    UUID tenantId = Uuidv7.generate();
    String slug = "concurrent-last-admin-" + UUID.randomUUID();
    seedTenant(tenantId, slug);

    UUID adminAId;
    UUID adminBId;
    TenantContext.set(tenantId, slug);
    try {
      roleCatalogSeeder.ensureCatalog(tenantId);
      CreatedUser adminA =
          userAdmin.create(
              "admin-a-" + UUID.randomUUID().toString().substring(0, 8),
              new LocalizedText("Admin A", "المدير أ"),
              Set.of("TENANT_ADMIN"));
      CreatedUser adminB =
          userAdmin.create(
              "admin-b-" + UUID.randomUUID().toString().substring(0, 8),
              new LocalizedText("Admin B", "المدير ب"),
              Set.of("TENANT_ADMIN"));
      adminAId = adminA.id();
      adminBId = adminB.id();
    } finally {
      TenantContext.clear();
    }

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger lastAdminConflicts = new AtomicInteger();

    try {
      List<UUID> targets = List.of(adminAId, adminBId);
      List<Future<Void>> futures = new java.util.ArrayList<>();
      for (UUID targetId : targets) {
        Callable<Void> task =
            () -> {
              TenantContext.set(tenantId, slug);
              try {
                ready.countDown();
                start.await();
                try {
                  userAdmin.lock(targetId);
                  successes.incrementAndGet();
                } catch (ConflictException e) {
                  lastAdminConflicts.incrementAndGet();
                }
              } finally {
                TenantContext.clear();
              }
              return null;
            };
        futures.add(pool.submit(task));
      }

      ready.await(10, TimeUnit.SECONDS);
      start.countDown();
      for (Future<Void> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdown();
    }

    assertThat(successes.get()).as("exactly one lock must succeed").isEqualTo(1);
    assertThat(lastAdminConflicts.get())
        .as("exactly one must be rejected as the last active admin")
        .isEqualTo(1);

    // RLS-protected tables (app_user/user_role/role) only return rows under the matching
    // app.tenant_id — the assertion query must run under the race tenant's own context, same as
    // every write above.
    TenantContext.set(tenantId, slug);
    Long activeAdmins;
    try {
      activeAdmins =
          jdbc.queryForObject(
              "SELECT COUNT(DISTINCT au.id) FROM app_user au JOIN user_role ur ON ur.user_id ="
                  + " au.id JOIN role r ON r.id = ur.role_id WHERE au.tenant_id = ? AND au.status ="
                  + " 'ACTIVE' AND 'tenant:admin' = ANY(r.scopes)",
              Long.class,
              tenantId);
    } finally {
      TenantContext.clear();
    }
    assertThat(activeAdmins).as("the tenant must retain exactly one active admin").isEqualTo(1L);
  }

  private void seedTenant(UUID tenantId, String slug) {
    jdbc.update(
        "INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status) VALUES"
            + " (?, ?, '{\"en\":\"Race Tenant\",\"ar\":\"مستأجر السباق\"}'::jsonb, 'GOVERNMENT',"
            + " 'SAAS', 'ACTIVE')",
        tenantId,
        slug);
  }
}
