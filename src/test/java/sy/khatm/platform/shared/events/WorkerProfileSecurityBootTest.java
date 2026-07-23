package sy.khatm.platform.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Spec FS-0.6b DoD #9 (second half) — extends {@link WorkerRoleGuardTest}'s guard, this time with a
 * real full application boot: the {@code worker} role still starts cleanly now that Spring Security
 * is unconditionally on the classpath (spec §3 — {@code rbac.security.SecurityConfig} is not gated
 * by {@code khatm.web.enabled}), and the role split still holds under it — both {@link
 * SecurityFilterChain} beans (the api-key / session split, spec §3) are present (Spring Security
 * itself loads in every role), but the two business REST controllers ({@code CredentialController},
 * {@code JwksController}) are absent, exactly as ADR-09 already established before this session —
 * and, new this session, so is {@code key.domain.KeyBootstrap} (the KH-0.3-closure race-condition
 * flag's fix: only the {@code api} role ever bootstraps the shared PKCS#12 keystore now).
 *
 * <p><b>KH-1.6-early:</b> {@code rbac.domain.AdminBootstrap} joined the absence list too — it had
 * the identical unguarded-race shape {@code KeyBootstrap} was fixed for above, just not caught in
 * the same session; a `compose-smoke` CI failure (both roles racing to insert the same bootstrap
 * admin row) surfaced it for real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerProfileSecurityBootTest {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
  private static final Path KEYSTORE;

  static {
    POSTGRES.start();
    REDIS.start();
    try {
      KEYSTORE = Files.createTempFile("khatm-worker-security-boot-keys-", ".p12");
      Files.deleteIfExists(KEYSTORE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("khatm.worker.enabled", () -> "true");
    registry.add("khatm.web.enabled", () -> "false");
    registry.add("khatm.events.externalize", () -> "true");
    registry.add("khatm.keys.soft.keystore-path", KEYSTORE::toString);
    registry.add("khatm.keys.soft.passphrase", () -> "worker-security-boot-passphrase");
    registry.add("khatm.claims.enc-key", () -> "a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=");
    registry.add("khatm.auth.bootstrap.admin-username", () -> "worker-boot-admin");
    registry.add("khatm.auth.bootstrap.admin-password", () -> "worker-boot-admin-password");
    // chore/public-base-url: PublicUrlBuilder fails startup on a blank khatm.public-base-url
    // outside 'local' — this suite runs under no active profile.
    registry.add("khatm.public-base-url", () -> "http://localhost:8080");
  }

  @Autowired private ApplicationContext context;

  @Test
  void workerRole_bootsCleanly_withSecurityFilterChainPresent_andNoBusinessControllers() {
    assertThat(context).isNotNull();
    // Two chains since KH-0.6b (api-key / session split — see rbac.security.SecurityConfig).
    assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(2);

    assertThatThrownBy(() -> context.getBean("credentialController"))
        .isInstanceOf(NoSuchBeanDefinitionException.class);
    assertThatThrownBy(() -> context.getBean("jwksController"))
        .isInstanceOf(NoSuchBeanDefinitionException.class);
    assertThatThrownBy(() -> context.getBean("statusListController"))
        .isInstanceOf(NoSuchBeanDefinitionException.class);
    assertThatThrownBy(() -> context.getBean("keyBootstrap"))
        .isInstanceOf(NoSuchBeanDefinitionException.class);
    assertThatThrownBy(() -> context.getBean("adminBootstrap"))
        .isInstanceOf(NoSuchBeanDefinitionException.class);
  }
}
