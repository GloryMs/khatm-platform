package sy.khatm.platform.key.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Fix for the KH-0.3-closure "Open decisions / blockers" flag: {@link KeyBootstrap} is now gated
 * behind {@code khatm.web.enabled} exactly like {@code CredentialController}/{@code JwksController}
 * (ADR-09), so only the {@code api} role ever runs it and the {@code khatm_keys} PKCS#12 keystore
 * has a single writer at first boot. Same lightweight-context pattern as {@code
 * shared.events.WorkerRoleGuardTest}: no full Spring Boot context, no containers — {@link
 * ApplicationContextRunner} only evaluates the conditional wiring, never invokes {@link
 * KeyBootstrap#run}, which is Spring Boot's {@code SpringApplication.callRunners()} job and out of
 * scope for this bean-presence check.
 */
class KeyBootstrapRoleGuardTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(KeyBootstrap.class)
          .withBean(KeyLifecycleService.class, () -> mock(KeyLifecycleService.class));

  @Test
  void webEnabled_loadsKeyBootstrap() {
    runner
        .withPropertyValues("khatm.web.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(KeyBootstrap.class));
  }

  @Test
  void webEnabledMissing_apiDefault_loadsKeyBootstrap() {
    // matchIfMissing = true: the api/local/default profile documents never set khatm.web.enabled
    // explicitly to true — this is the actual production shape, not just a test convenience.
    runner.run(context -> assertThat(context).hasSingleBean(KeyBootstrap.class));
  }

  @Test
  void webDisabled_workerRole_doesNotLoadKeyBootstrap() {
    runner
        .withPropertyValues("khatm.web.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(KeyBootstrap.class));
  }
}
