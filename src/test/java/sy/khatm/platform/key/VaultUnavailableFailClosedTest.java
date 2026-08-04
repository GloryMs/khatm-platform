package sy.khatm.platform.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;
import sy.khatm.platform.KhatmPlatformApplication;
import sy.khatm.platform.key.api.KeySigner;
import sy.khatm.platform.key.api.KeyVerifier;
import sy.khatm.platform.key.api.PublicKeyHandle;
import sy.khatm.platform.key.domain.IssuerKeySummary;
import sy.khatm.platform.key.domain.KeyLifecycleService;
import sy.khatm.platform.key.domain.PublishedKey;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.IntegrityException;

/**
 * Session brief (spec FS-2.3 D5/D6): "Failure mode: Vault unreachable at sign time =&gt;
 * fail-closed with a distinct error + alarm-friendly log (no silent SOFT fallback — a fallback
 * would be a key-security downgrade, forbidden)." Plus the DoD's own bracketed claim, verified here
 * rather than assumed: "public artifacts don't need Vault at read time."
 *
 * <p>Its own dedicated test class (not folded into {@link VaultKeyLifecycleAcceptanceTest}) because
 * it needs to actually stop the Vault container mid-test — an irreversible action for that
 * container that would break every other test sharing it.
 */
@Testcontainers
class VaultUnavailableFailClosedTest {

  private static final String VAULT_TOKEN = "vault-fail-closed-test-root-token";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("db/khatm-app-role-init.sql");

  @Container
  static final VaultContainer<?> VAULT =
      new VaultContainer<>(DockerImageName.parse("hashicorp/vault:1.17"))
          .withVaultToken(VAULT_TOKEN)
          .withInitCommand("secrets enable transit");

  @TempDir private Path tempDir;

  @Test
  void vaultUnreachableAtSignTime_failsClosed_neverSilentlyFallsBackToSoft_publicReadsStillWork()
      throws Exception {
    try (ConfigurableApplicationContext context = buildContext()) {
      KeyLifecycleService lifecycle = context.getBean(KeyLifecycleService.class);
      KeySigner keySigner = context.getBean(KeySigner.class);
      KeyVerifier keyVerifier = context.getBean(KeyVerifier.class);
      UUID tenantId = TenantContext.current();
      String tenantSlug = TenantContext.currentSlug();

      IssuerKeySummary vaultKey = lifecycle.rotate(tenantId, tenantSlug, "VAULT");
      assertThat(vaultKey.provider()).isEqualTo("VAULT");
      // Vault healthy here — sanity-checks the rest of this test's premise before killing it.
      assertThat(keySigner.sign(sampleClaims()).kid()).isEqualTo(vaultKey.kid());

      VAULT.stop();

      // Sign time: the tenant's ACTIVE key is Vault-backed and Vault is now unreachable.
      assertThatThrownBy(() -> keySigner.sign(sampleClaims()))
          .isInstanceOf(IntegrityException.class)
          .satisfies(
              e ->
                  assertThat(((IntegrityException) e).errorCode())
                      .isEqualTo(ErrorCode.KH_KEY_0503));

      // Key-creation time (a rotation onto Vault attempted while Vault is down) fails the same way
      // — never silently falls back to creating a SOFT key instead, which would be exactly the
      // key-security downgrade the brief forbids.
      assertThatThrownBy(() -> lifecycle.rotate(tenantId, tenantSlug, "VAULT"))
          .isInstanceOf(IntegrityException.class)
          .satisfies(
              e ->
                  assertThat(((IntegrityException) e).errorCode())
                      .isEqualTo(ErrorCode.KH_KEY_0503));
      // The failed rotation attempt must not have left the tenant with no ACTIVE key at all, nor
      // created a second ACTIVE row — the old Vault key (created before Vault died) is still it.
      assertThat(activeKidOf(lifecycle, tenantId)).isEqualTo(vaultKey.kid());

      // "Public artifacts don't need Vault at read time" — verified, not assumed: both signature
      // verification (KeyVerifier) and the JWKS listing (KeyLifecycleService) read only
      // issuer_key.public_jwk, never call VaultTransitProvider, so both keep working.
      Optional<PublicKeyHandle> handle = keyVerifier.resolvePublicKey(vaultKey.kid());
      assertThat(handle).as("resolvePublicKey must not need Vault reachable").isPresent();

      assertThat(lifecycle.publishableKeysForDefaultTenantJwks(tenantId))
          .extracting(PublishedKey::kid)
          .contains(vaultKey.kid());
    }
  }

  private static String activeKidOf(KeyLifecycleService lifecycle, UUID tenantId) {
    return lifecycle.listAllStatuses(tenantId).stream()
        .filter(k -> "ACTIVE".equals(k.state()))
        .findFirst()
        .orElseThrow()
        .kid();
  }

  private ConfigurableApplicationContext buildContext() {
    return new SpringApplicationBuilder(KhatmPlatformApplication.class)
        .web(WebApplicationType.NONE)
        .profiles("test")
        .run(
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--khatm.keys.soft.keystore-path=" + tempDir.resolve("vault-fail-closed-keys.p12"),
            "--khatm.keys.soft.passphrase=vault-fail-closed-test-passphrase",
            "--khatm.keys.vault.enabled=true",
            "--khatm.keys.vault.address=" + VAULT.getHttpHostAddress(),
            "--khatm.keys.vault.token=" + VAULT_TOKEN,
            "--khatm.keys.vault.key-name-prefix=khatm-fail-closed",
            // Short timeouts so the sign-time-unreachable assertions don't hang on the default
            // 3s/5s connect/read timeouts once Vault is stopped — a closed port fails fast anyway
            // (connection refused), but this keeps the test itself fast and explicit about intent.
            "--khatm.keys.vault.connect-timeout=PT1S",
            "--khatm.keys.vault.read-timeout=PT1S",
            "--khatm.claims.enc-key=a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=",
            "--khatm.auth.totp.enc-key=a2hhdG0tdGVzdC10b3RwLWVuYy1rZXktMzJieXRlcyE=",
            "--khatm.auth.bootstrap.admin-username=test-admin",
            "--khatm.auth.bootstrap.admin-password=test-admin-password-change-me",
            "--khatm.public-base-url=http://localhost:8080");
  }

  private static JWTClaimsSet sampleClaims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .subject("vault-fail-closed-test")
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(3600)))
        .build();
  }
}
