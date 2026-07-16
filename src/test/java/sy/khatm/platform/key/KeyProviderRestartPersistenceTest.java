package sy.khatm.platform.key;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
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
import sy.khatm.platform.key.api.PublicKeyHandle;
import sy.khatm.platform.key.api.SignResult;

/**
 * FS-0.5 §8 DoD #2 — the single most important acceptance criterion: a signature issued by one
 * application run must still verify, under the same {@code kid}, after a full restart against the
 * same keystore file and database. This is exactly what the old ephemeral in-memory {@code
 * SoftKeyService} could never do.
 *
 * <p>Deliberately does <em>not</em> extend {@code IntegrationTestSupport} — that base's
 * shared-context pattern reuses one {@code ApplicationContext} for the whole test suite, which is
 * the opposite of what this test needs (two independent, sequential contexts). Uses its own
 * dedicated Postgres container and a real {@code SpringApplicationBuilder} run/close cycle to
 * simulate an actual process restart.
 */
@Testcontainers
class KeyProviderRestartPersistenceTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir private Path tempDir;

  @Test
  void signature_survivesApplicationRestart_sameKeystoreAndDatabase() throws Exception {
    Path keystorePath = tempDir.resolve("restart-persistence-keys.p12");
    String passphrase = "restart-persistence-test-passphrase";

    SignResult signed;
    try (ConfigurableApplicationContext firstRun = buildContext(keystorePath, passphrase)) {
      KeySigner signer = firstRun.getBean(KeySigner.class);
      signed = signer.sign(sampleClaims());
    }
    // firstRun is now closed — simulates the application process exiting.

    try (ConfigurableApplicationContext secondRun = buildContext(keystorePath, passphrase)) {
      KeyVerifier verifier = secondRun.getBean(KeyVerifier.class);

      Optional<PublicKeyHandle> handle = verifier.resolvePublicKey(signed.kid());
      assertThat(handle).as("kid '%s' must still resolve after restart", signed.kid()).isPresent();

      SignedJWT reparsed = SignedJWT.parse(signed.jws());
      boolean verifies = reparsed.verify(new ECDSAVerifier(handle.get().publicKey()));
      assertThat(verifies).as("signature from the first run must verify after restart").isTrue();
      assertThat(reparsed.getHeader().getKeyID()).isEqualTo(signed.kid());
    }
  }

  private static ConfigurableApplicationContext buildContext(Path keystorePath, String passphrase) {
    // Command-line-style args (not .properties(), which registers a lowest-precedence
    // "defaultProperties" source — application.yml's own spring.datasource.url entry would win
    // over it) so these actually override the yml defaults for each independent run.
    return new SpringApplicationBuilder(KhatmPlatformApplication.class)
        .web(WebApplicationType.NONE)
        .profiles("test")
        .run(
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--khatm.keys.soft.keystore-path=" + keystorePath,
            "--khatm.keys.soft.passphrase=" + passphrase,
            // KH-0.4: ClaimsEncryptionService also fails startup without this outside 'local'
            // (spec FS-0.4 D7) — a full context now needs both secrets, same as any real
            // deployment would.
            "--khatm.claims.enc-key=a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=");
  }

  private static JWTClaimsSet sampleClaims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .subject("restart-persistence-test")
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(3600)))
        .build();
  }
}
