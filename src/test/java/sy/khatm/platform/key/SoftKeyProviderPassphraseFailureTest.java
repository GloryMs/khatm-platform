package sy.khatm.platform.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
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
import sy.khatm.platform.key.api.KeySigner;
import sy.khatm.platform.key.api.KeyVerifier;

/**
 * FS-0.5 §8 DoD #5 — the two ways {@code SoftKeyProvider} must fail startup instead of silently
 * proceeding: a wrong passphrase against an <em>existing</em> keystore (must never be treated as
 * "create a fresh one"), and a missing passphrase outside the {@code local} profile.
 *
 * <p>Own dedicated Postgres container, isolated from every other key-module test, since a
 * successful sub-context here provisions a real {@code ACTIVE} issuer key against the database.
 */
@Testcontainers
class SoftKeyProviderPassphraseFailureTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir private Path tempDir;

  @Test
  void wrongPassphrase_onExistingKeystore_failsStartup_andNeverOverwritesTheFile()
      throws Exception {
    Path keystorePath = tempDir.resolve("wrong-passphrase-keys.p12");

    try (ConfigurableApplicationContext firstRun =
        buildContext(keystorePath, "correct-passphrase")) {
      firstRun.getBean(KeySigner.class).sign(sampleClaims()); // forces the keystore file to exist
    }
    long sizeAfterFirstRun = Files.size(keystorePath);
    assertThat(sizeAfterFirstRun).isGreaterThan(0);

    assertThatThrownBy(() -> buildContext(keystorePath, "wrong-passphrase"))
        .satisfies(ex -> assertThat(rootMessage(ex)).containsIgnoringCase("passphrase"));

    // The file must be byte-for-byte untouched — a silent "just make a new one" would have
    // orphaned every signature from firstRun.
    assertThat(Files.size(keystorePath)).isEqualTo(sizeAfterFirstRun);

    // And it must still open fine with the correct passphrase.
    try (ConfigurableApplicationContext thirdRun =
        buildContext(keystorePath, "correct-passphrase")) {
      assertThat(thirdRun.getBean(KeyVerifier.class)).isNotNull();
    }
  }

  @Test
  void missingPassphrase_outsideLocalProfile_failsStartup() {
    Path keystorePath = tempDir.resolve("missing-passphrase-keys.p12");

    assertThatThrownBy(() -> buildContext(keystorePath, null))
        .satisfies(ex -> assertThat(rootMessage(ex)).containsIgnoringCase("passphrase"));

    assertThat(Files.exists(keystorePath)).isFalse();
  }

  private static ConfigurableApplicationContext buildContext(Path keystorePath, String passphrase) {
    // Command-line-style args (not .properties(), which registers a lowest-precedence
    // "defaultProperties" source — application.yml's own entries would win over it).
    List<String> args =
        new ArrayList<>(
            List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--khatm.keys.soft.keystore-path=" + keystorePath,
                // KH-0.4: ClaimsEncryptionService also needs a valid key outside 'local' (spec
                // FS-0.4 D7) — kept valid here so these tests isolate the passphrase failure
                // specifically, not an unrelated one.
                "--khatm.claims.enc-key=a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=",
                // KH-0.6b: AdminBootstrap also needs valid bootstrap credentials outside 'local'
                // (spec FS-0.6b D10) — same isolation reasoning as the claims key above.
                "--khatm.auth.bootstrap.admin-username=test-admin",
                "--khatm.auth.bootstrap.admin-password=test-admin-password-change-me"));
    if (passphrase != null) {
      args.add("--khatm.keys.soft.passphrase=" + passphrase);
    }
    return new SpringApplicationBuilder(KhatmPlatformApplication.class)
        .web(WebApplicationType.NONE)
        .profiles("test") // deliberately not 'local' — passphrase must be explicit
        .run(args.toArray(new String[0]));
  }

  /**
   * Concatenate every message in the cause chain. The JDK's own {@code KeyStore.load} failure
   * (deepest cause, e.g. {@code BadPaddingException}) never mentions "passphrase" — the clear
   * message SoftKeyProvider wraps it in does, higher up the chain.
   */
  private static String rootMessage(Throwable ex) {
    StringBuilder combined = new StringBuilder();
    Throwable cursor = ex;
    while (cursor != null) {
      combined.append(cursor.getMessage()).append(" | ");
      cursor = cursor.getCause();
    }
    return combined.toString();
  }

  private static JWTClaimsSet sampleClaims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .subject("passphrase-failure-test")
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(3600)))
        .build();
  }
}
