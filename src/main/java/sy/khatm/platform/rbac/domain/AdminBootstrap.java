package sy.khatm.platform.rbac.domain;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.rbac.persistence.RoleRepository;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Auto-provisions the default tenant's first console user at startup (spec FS-0.6b D10).
 *
 * <p>Idempotent: a second startup against a database that already has any {@code app_user} row for
 * the tenant does nothing — mirrors {@code key.domain.KeyBootstrap}'s exact idempotency pattern
 * (spec FS-0.5 §5), applied here to "any user exists" rather than "an ACTIVE key exists."
 *
 * <p><b>No silent defaults outside {@code local} (D10):</b> {@code
 * khatm.auth.bootstrap.admin-username}/{@code admin-password} have no fallback in the base {@code
 * application.yml} document — only the {@code local} profile document supplies one, so by the time
 * this constructor runs under any other profile, a still-blank value means the operator genuinely
 * did not set {@code KHATM_BOOTSTRAP_ADMIN_USERNAME}/{@code KHATM_BOOTSTRAP_ADMIN_PASSWORD}, and
 * startup fails rather than silently skipping bootstrap or creating a guessable account (mirrors
 * {@code SoftKeyProvider}'s passphrase check and {@code ClaimsEncryptionService}'s key check, spec
 * FS-0.5 §3).
 *
 * <p><b>{@code khatm.web.enabled} gate (discovered KH-1.6-early, fixed here):</b> this class had no
 * role gate — the exact race {@code key.domain.KeyBootstrap}'s own Javadoc documents and was fixed
 * for at KH-0.3-closure, just not caught here at the same time. With {@code api} and {@code worker}
 * booting concurrently (ADR-09's unsequenced `docker compose up -d`), both roles ran this {@code
 * ApplicationRunner} against the same fresh database: both saw {@code
 * users.existsByTenantId(tenantId)} as {@code false} and both attempted the insert, and the loser's
 * unhandled {@code DataIntegrityViolationException} against the {@code
 * app_user_tenant_id_username_key} unique constraint crashed that container's startup entirely —
 * confirmed empirically via a `compose-smoke` CI failure (not merely theorized). Gated the same way
 * {@code CredentialController}/{@code JwksController}/{@code KeyBootstrap} already are so only the
 * {@code api} role ever runs it, making it a single writer again.
 */
@Component
@ConditionalOnProperty(name = "khatm.web.enabled", havingValue = "true", matchIfMissing = true)
class AdminBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
  private static final String PLATFORM_ADMIN_ROLE_CODE = "PLATFORM_ADMIN";

  private final AppUserRepository users;
  private final RoleRepository roles;
  private final PasswordEncoder passwordEncoder;
  private final AuditService audit;
  private final String adminUsername;
  private final String adminPassword;
  private final boolean localProfileActive;

  AdminBootstrap(
      AppUserRepository users,
      RoleRepository roles,
      PasswordEncoder passwordEncoder,
      AuditService audit,
      @Value("${khatm.auth.bootstrap.admin-username:}") String adminUsername,
      @Value("${khatm.auth.bootstrap.admin-password:}") String adminPassword,
      Environment environment) {
    this.users = users;
    this.roles = roles;
    this.passwordEncoder = passwordEncoder;
    this.audit = audit;
    this.adminUsername = adminUsername;
    this.adminPassword = adminPassword;
    this.localProfileActive = environment.acceptsProfiles(Profiles.of("local"));
  }

  // @Transactional lives here, not on bootstrapIfNeeded() below — Spring's proxy-based AOP only
  // intercepts calls that arrive from OUTSIDE this bean (run() is invoked externally by
  // SpringApplication's runner-invocation machinery, which does go through the proxy). A plain
  // internal this.bootstrapIfNeeded() self-invocation from inside run() would bypass the proxy
  // entirely and silently run with no transaction at all.
  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    bootstrapIfNeeded();
  }

  void bootstrapIfNeeded() {
    UUID tenantId = TenantContext.current();
    if (users.existsByTenantId(tenantId)) {
      log.info("An app_user already exists for the default tenant — admin bootstrap skipped");
      return;
    }
    if ((adminUsername.isBlank() || adminPassword.isBlank()) && !localProfileActive) {
      throw new IllegalStateException(
          "KHATM_BOOTSTRAP_ADMIN_USERNAME and KHATM_BOOTSTRAP_ADMIN_PASSWORD are required outside"
              + " the 'local' profile when no app_user exists yet. Refusing to start without a"
              + " way to provision the first console admin.");
    }

    var role =
        roles
            .findByTenantIdAndCode(tenantId, PLATFORM_ADMIN_ROLE_CODE)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Seed role '"
                            + PLATFORM_ADMIN_ROLE_CODE
                            + "' is missing for the default tenant — V1__baseline.sql should have"
                            + " seeded it."));

    AppUser user = new AppUser();
    user.setId(Uuidv7.generate());
    user.setTenantId(tenantId);
    user.setUsername(adminUsername);
    user.setPasswordHash(passwordEncoder.encode(adminPassword));
    user.setDisplayNameI18n(new LocalizedText("Bootstrap Administrator", "مسؤول التمهيد"));
    user.setPreferredLang("ar");
    user.setStatus("ACTIVE");
    user.setCreatedAt(Instant.now());
    users.save(user);
    roles.assignRole(user.getId(), role.getId());

    audit.record(AuditAction.USER_CREATED, "app_user", adminUsername, null);
    log.info("Bootstrapped console admin user username={}", adminUsername);
  }
}
