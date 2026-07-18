package sy.khatm.platform.rbac.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.rbac.persistence.RoleRepository;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * KH-1.6-early — {@link AdminBootstrap} is now gated behind {@code khatm.web.enabled} exactly like
 * {@code key.domain.KeyBootstrap} (ADR-09), closing the same race a `compose-smoke` CI run
 * surfaced: both {@code api} and {@code worker} running this {@code ApplicationRunner}
 * unconditionally against the same fresh database. Same lightweight-context pattern as {@code
 * key.domain.KeyBootstrapRoleGuardTest}: no full Spring Boot context, no containers — {@link
 * ApplicationContextRunner} only evaluates the conditional wiring, never invokes {@link
 * AdminBootstrap#run}.
 */
class AdminBootstrapRoleGuardTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(AdminBootstrap.class)
          .withBean(AppUserRepository.class, () -> mock(AppUserRepository.class))
          .withBean(RoleRepository.class, () -> mock(RoleRepository.class))
          .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
          .withBean(AuditService.class, () -> mock(AuditService.class));

  @Test
  void webEnabled_loadsAdminBootstrap() {
    runner
        .withPropertyValues("khatm.web.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(AdminBootstrap.class));
  }

  @Test
  void webEnabledMissing_apiDefault_loadsAdminBootstrap() {
    // matchIfMissing = true: the api/local/default profile documents never set khatm.web.enabled
    // explicitly to true — this is the actual production shape, not just a test convenience.
    runner.run(context -> assertThat(context).hasSingleBean(AdminBootstrap.class));
  }

  @Test
  void webDisabled_workerRole_doesNotLoadAdminBootstrap() {
    runner
        .withPropertyValues("khatm.web.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(AdminBootstrap.class));
  }
}
