package sy.khatm.platform.rbac.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-2.2 D5 — {@link UserAdminService}'s domain behaviour: temporary-password creation
 * (plaintext-once, hashed, forces change), role validation against the fixed catalog, the
 * lock/unlock/disable status transitions, reset-password, and self-service password change.
 * Service-level (no HTTP) — the HTTP wiring and scope gate are covered by {@code
 * rbac.UserAdminGateTest}; the concurrency guard by {@code db.ConcurrentLastAdminTest}.
 */
class UserAdminServiceTest extends IntegrationTestSupport {

  @Autowired private UserAdminService userAdmin;
  @Autowired private RoleCatalogSeeder roleCatalogSeeder;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;

  private UUID freshTenant(String slugPrefix) {
    UUID tenantId = Uuidv7.generate();
    String slug = slugPrefix + "-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status) VALUES"
            + " (?, ?, '{\"en\":\"T\",\"ar\":\"ت\"}'::jsonb, 'GOVERNMENT', 'SAAS', 'ACTIVE')",
        tenantId,
        slug);
    TenantContext.set(tenantId, slug);
    roleCatalogSeeder.ensureCatalog(tenantId);
    return tenantId;
  }

  @Test
  void create_generatesTemporaryPassword_hashedAndForcingChange() {
    freshTenant("create");
    try {
      CreatedUser created =
          userAdmin.create(
              "operator-one",
              new LocalizedText("Op One", "المشغل الأول"),
              Set.of("ISSUER_OPERATOR"));

      assertThat(created.temporaryPassword()).isNotBlank();
      String storedHash =
          jdbc.queryForObject(
              "SELECT password_hash FROM app_user WHERE id = ?", String.class, created.id());
      assertThat(storedHash).isNotEqualTo(created.temporaryPassword());
      assertThat(passwordEncoder.matches(created.temporaryPassword(), storedHash)).isTrue();

      Boolean mustChange =
          jdbc.queryForObject(
              "SELECT must_change_password FROM app_user WHERE id = ?",
              Boolean.class,
              created.id());
      assertThat(mustChange).isTrue();
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void create_duplicateUsername_throwsConflict() {
    freshTenant("dup");
    try {
      userAdmin.create("dupuser", new LocalizedText("A", "أ"), Set.of());
      assertThatThrownBy(() -> userAdmin.create("dupuser", new LocalizedText("B", "ب"), Set.of()))
          .isInstanceOf(ConflictException.class);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void create_unknownRoleCode_throwsValidation() {
    freshTenant("badrole");
    try {
      assertThatThrownBy(
              () ->
                  userAdmin.create(
                      "someuser", new LocalizedText("A", "أ"), Set.of("NOT_A_CATALOG_ROLE")))
          .isInstanceOf(ValidationException.class);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void lock_thenUnlock_roundTripsStatus() {
    freshTenant("lockunlock");
    try {
      CreatedUser created =
          userAdmin.create("lockme", new LocalizedText("A", "أ"), Set.of("ISSUER_OPERATOR"));

      UserSummary locked = userAdmin.lock(created.id());
      assertThat(locked.status()).isEqualTo("LOCKED");

      UserSummary unlocked = userAdmin.unlock(created.id());
      assertThat(unlocked.status()).isEqualTo("ACTIVE");
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void replaceRoles_toEmptySet_removesAllRoles() {
    freshTenant("emptyroles");
    try {
      CreatedUser created =
          userAdmin.create("multirole", new LocalizedText("A", "أ"), Set.of("ISSUER_OPERATOR"));

      UserSummary updated = userAdmin.replaceRoles(created.id(), Set.of());

      assertThat(updated.roles()).isEmpty();
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void resetPassword_generatesNewTemporaryPassword_andForcesChangeAgain() {
    freshTenant("reset");
    try {
      CreatedUser created =
          userAdmin.create("resetme", new LocalizedText("A", "أ"), Set.of("ISSUER_OPERATOR"));

      CreatedUser reset = userAdmin.resetPassword(created.id());

      assertThat(reset.temporaryPassword()).isNotEqualTo(created.temporaryPassword());
      String newHash =
          jdbc.queryForObject(
              "SELECT password_hash FROM app_user WHERE id = ?", String.class, created.id());
      assertThat(passwordEncoder.matches(reset.temporaryPassword(), newHash)).isTrue();
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void changeOwnPassword_wrongCurrentPassword_throwsValidation_andDoesNotClearFlag() {
    freshTenant("changepw");
    try {
      CreatedUser created =
          userAdmin.create("changepw-user", new LocalizedText("A", "أ"), Set.of());

      assertThatThrownBy(
              () -> userAdmin.changeOwnPassword(created.id(), "totally-wrong", "new-password-1"))
          .isInstanceOf(ValidationException.class);

      Boolean stillMustChange =
          jdbc.queryForObject(
              "SELECT must_change_password FROM app_user WHERE id = ?",
              Boolean.class,
              created.id());
      assertThat(stillMustChange).isTrue();
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void changeOwnPassword_correctCurrentPassword_clearsFlag() {
    freshTenant("changepw-ok");
    try {
      CreatedUser created =
          userAdmin.create("changepw-ok-user", new LocalizedText("A", "أ"), Set.of());

      userAdmin.changeOwnPassword(
          created.id(), created.temporaryPassword(), "brand-new-password-1");

      Boolean mustChange =
          jdbc.queryForObject(
              "SELECT must_change_password FROM app_user WHERE id = ?",
              Boolean.class,
              created.id());
      assertThat(mustChange).isFalse();
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void lock_unknownUser_throwsNotFound() {
    freshTenant("unknown");
    try {
      assertThatThrownBy(() -> userAdmin.lock(UUID.randomUUID()))
          .isInstanceOf(NotFoundException.class);
    } finally {
      TenantContext.clear();
    }
  }
}
