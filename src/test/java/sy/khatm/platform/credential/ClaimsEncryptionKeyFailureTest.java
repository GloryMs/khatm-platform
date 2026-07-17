package sy.khatm.platform.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
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
 * FS-0.4 §6 DoD #5 (fail-fast half) — {@code ClaimsEncryptionService} refuses to start without a
 * usable {@code khatm.claims.enc-key} outside the {@code local} profile: missing entirely, or
 * present but not exactly 32 bytes once base64-decoded. Mirrors {@code
 * SoftKeyProviderPassphraseFailureTest}'s pattern exactly (spec FS-0.4 D7).
 *
 * <p>Own dedicated Postgres container — a successful sub-context here would provision real DB
 * state, and isolation keeps that from leaking into other test classes.
 */
@Testcontainers
class ClaimsEncryptionKeyFailureTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir private Path tempDir;

  @Test
  void missingEncKey_outsideLocalProfile_failsStartup() {
    assertThatThrownBy(() -> buildContext(null))
        .satisfies(ex -> assertThat(rootMessage(ex)).containsIgnoringCase("enc-key"));
  }

  @Test
  void wrongLengthEncKey_failsStartup() {
    // "too-short-for-aes-256" base64-encoded decodes to far fewer than 32 bytes.
    String shortKey =
        Base64.getEncoder()
            .encodeToString("too-short-for-aes-256".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> buildContext(shortKey))
        .satisfies(ex -> assertThat(rootMessage(ex)).contains("32"));
  }

  private ConfigurableApplicationContext buildContext(String encKeyBase64) {
    Path keystorePath = tempDir.resolve("claims-key-failure-test-keys.p12");
    List<String> args =
        new ArrayList<>(
            List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                // Kept valid so these tests isolate the claims-key failure specifically.
                "--khatm.keys.soft.keystore-path=" + keystorePath,
                "--khatm.keys.soft.passphrase=claims-key-failure-test-passphrase",
                // KH-0.6b: AdminBootstrap also fails startup without these outside 'local' (spec
                // FS-0.6b D10) — kept valid for the same isolation reason as the two above.
                "--khatm.auth.bootstrap.admin-username=test-admin",
                "--khatm.auth.bootstrap.admin-password=test-admin-password-change-me"));
    if (encKeyBase64 != null) {
      args.add("--khatm.claims.enc-key=" + encKeyBase64);
    }
    return new SpringApplicationBuilder(KhatmPlatformApplication.class)
        .web(WebApplicationType.NONE)
        .profiles("test") // deliberately not 'local' — the enc-key must be explicit
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
