package sy.khatm.platform.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sy.khatm.platform.KhatmPlatformApplication;

/**
 * chore/public-base-url — {@link PublicUrlBuilder} refuses to start without a usable {@code
 * khatm.public-base-url} outside the {@code local} profile. Mirrors {@code
 * SoftKeyProviderPassphraseFailureTest}/{@code ClaimsEncryptionKeyFailureTest}'s pattern exactly.
 *
 * <p>Own dedicated Postgres container — a successful sub-context here would provision real DB
 * state, and isolation keeps that from leaking into other test classes.
 */
@Testcontainers
class PublicUrlBuilderFailureTest {

  @Container
  // KH-2.1 (spec FS-2.1 D3): provisions khatm_app before Flyway's first migration run —
  // V7__rls_policies.sql GRANTs to it, so it must already exist even though this test's own
  // app datasource keeps using the container's owner role (no RLS-specific assertions here).
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("db/khatm-app-role-init.sql");

  @TempDir private Path tempDir;

  @Test
  void missingPublicBaseUrl_outsideLocalProfile_failsStartup() {
    assertThatThrownBy(() -> buildContext(null))
        .satisfies(ex -> assertThat(rootMessage(ex)).containsIgnoringCase("public-base-url"));
  }

  @Test
  void blankPublicBaseUrl_outsideLocalProfile_failsStartup() {
    assertThatThrownBy(() -> buildContext(""))
        .satisfies(ex -> assertThat(rootMessage(ex)).containsIgnoringCase("public-base-url"));
  }

  private ConfigurableApplicationContext buildContext(String publicBaseUrl) {
    Path keystorePath = tempDir.resolve("public-url-failure-test-keys.p12");
    List<String> args =
        new ArrayList<>(
            List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                // Kept valid so these tests isolate the public-base-url failure specifically.
                "--khatm.keys.soft.keystore-path=" + keystorePath,
                "--khatm.keys.soft.passphrase=public-url-failure-test-passphrase",
                "--khatm.claims.enc-key=a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=",
                "--khatm.auth.bootstrap.admin-username=test-admin",
                "--khatm.auth.bootstrap.admin-password=test-admin-password-change-me"));
    if (publicBaseUrl != null) {
      args.add("--khatm.public-base-url=" + publicBaseUrl);
    }
    return new SpringApplicationBuilder(KhatmPlatformApplication.class)
        .web(WebApplicationType.NONE)
        .profiles("test") // deliberately not 'local' — public-base-url must be explicit
        .run(args.toArray(new String[0]));
  }

  private static String rootMessage(Throwable ex) {
    StringBuilder combined = new StringBuilder();
    Throwable cursor = ex;
    while (cursor != null) {
      combined.append(cursor.getMessage()).append(" | ");
      cursor = cursor.getCause();
    }
    return combined.toString();
  }
}
