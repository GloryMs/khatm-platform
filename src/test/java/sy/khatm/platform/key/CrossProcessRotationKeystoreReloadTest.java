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
import sy.khatm.platform.key.domain.IssuerKeySummary;
import sy.khatm.platform.key.domain.KeyLifecycleService;
import sy.khatm.platform.shared.TenantContext;

/**
 * Spec FS-2.3 D2 — a real bug found while running this session's own live compose DoD (recorded
 * here per the brief's own verify-first discipline, not silently patched): {@code khatm-api} and
 * {@code khatm-worker} are separate JVMs sharing one {@code SoftKeyProvider} keystore <em>file</em>
 * (one Docker volume) but each holds its own in-memory {@link
 * sy.khatm.platform.key.domain.SoftKeyProvider} copy, loaded once at startup. Before this session's
 * fix, a rotation performed by one process (typically {@code khatm-api}, which serves the rotate
 * endpoint) persisted the new key to the shared file but left the <em>other</em> already-running
 * process's in-memory copy stale — {@code khatm-worker}'s {@code StatusListPublisher} then failed
 * every re-sign attempt for the rotated tenant with {@code JOSEException: No such key in keystore}
 * until its next restart, which would have silently broken D3's own "the sweep re-signs within one
 * cycle" guarantee in the actual multi-role deployment this platform uses (ADR-09).
 *
 * <p>Models the two roles as two independently-loaded {@code ApplicationContext}s, both alive at
 * once, sharing the same keystore file and the same Postgres database — the same shape {@code
 * KeyProviderRestartPersistenceTest} uses for a sequential restart, but concurrent here instead of
 * sequential, which is what actually reproduces the bug (a restart naturally reloads from disk; two
 * live processes do not).
 */
@Testcontainers
class CrossProcessRotationKeystoreReloadTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("db/khatm-app-role-init.sql");

  @TempDir private Path tempDir;

  @Test
  void rotationByOneProcess_isPickedUpByAnAlreadyRunningSecondProcess_noRestartNeeded()
      throws Exception {
    Path keystorePath = tempDir.resolve("cross-process-rotation-keys.p12");
    String passphrase = "cross-process-rotation-test-passphrase";

    try (ConfigurableApplicationContext apiLike = buildContext(keystorePath, passphrase);
        ConfigurableApplicationContext workerLike = buildContext(keystorePath, passphrase)) {

      // workerLike started second, against the same DB — KeyBootstrap no-ops there (an ACTIVE key
      // already exists), but its own SoftKeyProvider still independently loaded the shared
      // keystore FILE at its own startup, so it already has the pre-rotation key in memory too.
      KeySigner workerSignerBeforeRotation = workerLike.getBean(KeySigner.class);
      SignResult beforeRotation = workerSignerBeforeRotation.sign(sampleClaims());

      // Rotate via apiLike only — workerLike is never touched directly here, simulating the real
      // deployment where only khatm-api serves the rotate endpoint.
      KeyLifecycleService lifecycle = apiLike.getBean(KeyLifecycleService.class);
      IssuerKeySummary rotated =
          lifecycle.rotate(TenantContext.current(), TenantContext.currentSlug());
      assertThat(rotated.kid()).isNotEqualTo(beforeRotation.kid());

      // workerLike's own SoftKeyProvider has never seen the new key — this is the exact call that
      // failed with "No such key in keystore" before this session's reload-on-miss fix.
      KeySigner workerSignerAfterRotation = workerLike.getBean(KeySigner.class);
      SignResult afterRotation = workerSignerAfterRotation.sign(sampleClaims());
      assertThat(afterRotation.kid()).isEqualTo(rotated.kid());

      // The signature workerLike just produced with the reloaded key must actually verify —
      // apiLike resolves the public key strictly by kid, proving it isn't a coincidental match.
      KeyVerifier apiVerifier = apiLike.getBean(KeyVerifier.class);
      Optional<PublicKeyHandle> handle = apiVerifier.resolvePublicKey(afterRotation.kid());
      assertThat(handle).isPresent();
      SignedJWT reparsed = SignedJWT.parse(afterRotation.jws());
      assertThat(reparsed.verify(new ECDSAVerifier(handle.get().publicKey()))).isTrue();
    }
  }

  private static ConfigurableApplicationContext buildContext(Path keystorePath, String passphrase) {
    return new SpringApplicationBuilder(KhatmPlatformApplication.class)
        .web(WebApplicationType.NONE)
        .profiles("test")
        .run(
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--khatm.keys.soft.keystore-path=" + keystorePath,
            "--khatm.keys.soft.passphrase=" + passphrase,
            "--khatm.claims.enc-key=a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=",
            "--khatm.auth.totp.enc-key=a2hhdG0tdGVzdC10b3RwLWVuYy1rZXktMzJieXRlcyE=",
            "--khatm.auth.bootstrap.admin-username=test-admin",
            "--khatm.auth.bootstrap.admin-password=test-admin-password-change-me",
            "--khatm.public-base-url=http://localhost:8080");
  }

  private static JWTClaimsSet sampleClaims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .subject("cross-process-rotation-test")
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(3600)))
        .build();
  }
}
