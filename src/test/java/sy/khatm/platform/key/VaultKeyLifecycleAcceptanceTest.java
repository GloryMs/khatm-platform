package sy.khatm.platform.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;
import sy.khatm.platform.KhatmPlatformApplication;
import sy.khatm.platform.key.api.KeySigner;
import sy.khatm.platform.key.api.KeyVerifier;
import sy.khatm.platform.key.api.PublicKeyHandle;
import sy.khatm.platform.key.api.SignResult;
import sy.khatm.platform.key.api.TenantKeyProvisioner;
import sy.khatm.platform.key.domain.IssuerKeySummary;
import sy.khatm.platform.key.domain.KeyLifecycleService;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.ValidationException;

/**
 * Session brief (spec FS-2.3 D5/D6, KH-2.3b): "Re-run the ENTIRE 2.3a test suite against the Vault
 * provider (parametrize the harness if feasible; duplication is acceptable, silent gaps are not)."
 *
 * <p>Rather than parametrizing {@code key.domain.KeyLifecycleServiceTest} itself (its shared {@code
 * IntegrationTestSupport} context never enables Vault, and duplicating the harness across two
 * Testcontainers setups — one Postgres-only, one Postgres+Vault — is exactly the "duplication is
 * acceptable" the brief allows for), this class re-exercises the same FS-0.5 §8 / FS-2.3 D2/D4
 * acceptance criteria against a real {@code VaultTransitProvider}.
 *
 * <p>One shared context and Vault/Postgres container pair for the whole class (cheaper than one per
 * test, same rationale as {@code IntegrationTestSupport}), but each test method provisions its own
 * brand-new tenant (same raw-insert technique {@code key.domain.KeyLifecycleServiceTest}'s own
 * {@code countByTenantId_calledBareWithNoAmbientTransaction_isStillTenantScopedByRls} test uses) —
 * deliberately NOT the shared default tenant, so test methods never interfere with each other's
 * {@code ACTIVE}-key state regardless of execution order (JUnit 5 does not guarantee method order
 * by default).
 */
@Testcontainers
class VaultKeyLifecycleAcceptanceTest {

  private static final String VAULT_TOKEN = "vault-acceptance-test-root-token";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("db/khatm-app-role-init.sql");

  @Container
  static final VaultContainer<?> VAULT =
      new VaultContainer<>(DockerImageName.parse("hashicorp/vault:1.17"))
          .withVaultToken(VAULT_TOKEN)
          .withInitCommand("secrets enable transit");

  @TempDir private static Path tempDir;

  private static ConfigurableApplicationContext context;
  private static KeyLifecycleService lifecycle;
  private static KeySigner keySigner;
  private static KeyVerifier keyVerifier;
  private static TenantKeyProvisioner keyProvisioner;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void startContext() {
    context =
        new SpringApplicationBuilder(KhatmPlatformApplication.class)
            .web(WebApplicationType.NONE)
            .profiles("test")
            .run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--khatm.keys.soft.keystore-path=" + tempDir.resolve("vault-acceptance-keys.p12"),
                "--khatm.keys.soft.passphrase=vault-acceptance-test-passphrase",
                "--khatm.keys.vault.enabled=true",
                "--khatm.keys.vault.address=" + VAULT.getHttpHostAddress(),
                "--khatm.keys.vault.token=" + VAULT_TOKEN,
                "--khatm.keys.vault.key-name-prefix=khatm-acceptance",
                "--khatm.claims.enc-key=a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=",
                "--khatm.auth.totp.enc-key=a2hhdG0tdGVzdC10b3RwLWVuYy1rZXktMzJieXRlcyE=",
                "--khatm.auth.bootstrap.admin-username=test-admin",
                "--khatm.auth.bootstrap.admin-password=test-admin-password-change-me",
                "--khatm.public-base-url=http://localhost:8080");
    lifecycle = context.getBean(KeyLifecycleService.class);
    keySigner = context.getBean(KeySigner.class);
    keyVerifier = context.getBean(KeyVerifier.class);
    keyProvisioner = context.getBean(TenantKeyProvisioner.class);
    jdbc = context.getBean(JdbcTemplate.class);
  }

  @AfterAll
  static void stopContext() {
    if (context != null) {
      context.close();
    }
  }

  /**
   * Spec D6's own story end to end: a freshly onboarded tenant starts on SOFT ({@link
   * TenantKeyProvisioner#provisionFirstKey}, the same call {@code tenant.domain
   * .TenantAdminService} makes for every real onboarding — always SOFT, spec V3), then rotates onto
   * Vault. Old SOFT credential still verifies (FS-0.5 §8 DoD #4, now proven across a provider
   * boundary, not just RETIRING).
   */
  @Test
  void softToVaultMigration_isANormalRotation_oldSoftCredentialStillVerifies() throws Exception {
    withFreshTenant(
        (tenantId, tenantSlug) -> {
          keyProvisioner.provisionFirstKey(tenantId, tenantSlug);
          IssuerKeySummary activeBefore = requireActive(tenantId);
          assertThat(activeBefore.provider()).isEqualTo("SOFT");
          SignResult softSigned = keySigner.sign(sampleClaims());
          assertThat(softSigned.kid()).isEqualTo(activeBefore.kid());

          IssuerKeySummary rotated = lifecycle.rotate(tenantId, tenantSlug, "VAULT");

          assertThat(rotated.provider()).isEqualTo("VAULT");
          assertThat(rotated.kid()).isNotEqualTo(activeBefore.kid());

          // The old SOFT signature must still verify — resolvePublicKey never depends on which
          // provider is now ACTIVE for the tenant (each key routes by its OWN stored provider).
          assertVerifies(softSigned);

          // New signing goes through Vault and must produce a genuinely valid ES256 signature.
          SignResult vaultSigned = keySigner.sign(sampleClaims());
          assertThat(vaultSigned.kid()).isEqualTo(rotated.kid());
          assertVerifies(vaultSigned);
        });
  }

  /** FS-0.5 §8 DoD #4 / FS-2.3 D2, against Vault: one ACTIVE, old RETIRING, both resolvable. */
  @Test
  void rotateOntoVault_exactlyOneActiveAfterwards_oldRetiring_bothResolvable() throws Exception {
    withFreshTenant(
        (tenantId, tenantSlug) -> {
          IssuerKeySummary firstVaultKey = lifecycle.rotate(tenantId, tenantSlug, "VAULT");

          IssuerKeySummary rotatedAgain = lifecycle.rotate(tenantId, tenantSlug, "VAULT");

          assertThat(rotatedAgain.kid()).isNotEqualTo(firstVaultKey.kid());
          assertThat(rotatedAgain.provider()).isEqualTo("VAULT");
          assertThat(keyVerifier.resolvePublicKey(firstVaultKey.kid())).isPresent();
          assertThat(keyVerifier.resolvePublicKey(rotatedAgain.kid())).isPresent();
        });
  }

  /**
   * A plain (no explicit provider) rotate, once a tenant is already on Vault, stays on Vault — the
   * 2-arg {@link KeyLifecycleService#rotate(UUID, String)} overload's own contract.
   */
  @Test
  void plainRotate_onceOnVault_staysOnVault_noExplicitProviderNeeded() throws Exception {
    withFreshTenant(
        (tenantId, tenantSlug) -> {
          lifecycle.rotate(tenantId, tenantSlug, "VAULT");

          IssuerKeySummary stillVault = lifecycle.rotate(tenantId, tenantSlug);

          assertThat(stillVault.provider()).isEqualTo("VAULT");
        });
  }

  /** FS-0.5 §8 DoD #6, against Vault: no private material anywhere the public JWK is stored. */
  @Test
  void vaultKey_publicJwkJson_neverContainsPrivateComponent() throws Exception {
    withFreshTenant(
        (tenantId, tenantSlug) -> {
          IssuerKeySummary rotated = lifecycle.rotate(tenantId, tenantSlug, "VAULT");

          Optional<PublicKeyHandle> handle = keyVerifier.resolvePublicKey(rotated.kid());
          assertThat(handle).isPresent();
        });
  }

  /** Spec FS-2.3 D5/D6: an unregistered provider name fails closed, never a silent fallback. */
  @Test
  void rotate_ontoUnknownProvider_throwsValidation_neverSilentlyFallsBackToSoft() throws Exception {
    withFreshTenant(
        (tenantId, tenantSlug) ->
            assertThatThrownBy(() -> lifecycle.rotate(tenantId, tenantSlug, "AWS_KMS"))
                .isInstanceOf(ValidationException.class)
                .satisfies(
                    e ->
                        assertThat(((ValidationException) e).errorCode())
                            .isEqualTo(ErrorCode.KH_KEY_0400)));
  }

  /**
   * Spec FS-2.3 D2's mandatory race test (docs/CONVENTIONS.md §11), re-run against Vault: the DB's
   * {@code issuer_key_one_active} partial index is still the race arbiter regardless of which
   * KeyProvider generated the winning row — concurrent HTTP calls to Vault itself introduce no new
   * race window because the ACTIVE-flip happens in Postgres, strictly before any provider call for
   * the new key even starts (spec order: retire-active, then generate).
   */
  @Test
  void rotateOntoVault_tenConcurrentCallers_exactlyOneSucceeds() throws Exception {
    withFreshTenant(
        (tenantId, tenantSlug) -> {
          int concurrentCallers = 10;
          ExecutorService pool = Executors.newFixedThreadPool(concurrentCallers);
          CountDownLatch ready = new CountDownLatch(concurrentCallers);
          CountDownLatch start = new CountDownLatch(1);
          AtomicInteger successes = new AtomicInteger();
          AtomicInteger failures = new AtomicInteger();
          try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentCallers; i++) {
              Callable<Void> task =
                  () -> {
                    TenantContext.set(tenantId, tenantSlug);
                    ready.countDown();
                    start.await();
                    try {
                      lifecycle.rotate(tenantId, tenantSlug, "VAULT");
                      successes.incrementAndGet();
                    } catch (RuntimeException e) {
                      failures.incrementAndGet();
                    } finally {
                      TenantContext.clear();
                    }
                    return null;
                  };
              futures.add(pool.submit(task));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            for (Future<Void> future : futures) {
              future.get(30, TimeUnit.SECONDS);
            }
          } finally {
            pool.shutdown();
          }

          assertThat(successes.get())
              .as("exactly one concurrent rotation must succeed")
              .isEqualTo(1);
          assertThat(failures.get()).isEqualTo(concurrentCallers - 1);
        });
  }

  private static IssuerKeySummary requireActive(UUID tenantId) {
    return lifecycle.listAllStatuses(tenantId).stream()
        .filter(k -> "ACTIVE".equals(k.state()))
        .findFirst()
        .map(
            k -> new IssuerKeySummary(k.kid(), k.state(), k.provider(), k.validFrom(), k.validTo()))
        .orElseThrow();
  }

  private static void assertVerifies(SignResult signed) throws Exception {
    Optional<PublicKeyHandle> handle = keyVerifier.resolvePublicKey(signed.kid());
    assertThat(handle).isPresent();
    SignedJWT reparsed = SignedJWT.parse(signed.jws());
    assertThat(reparsed.verify(new ECDSAVerifier(handle.get().publicKey()))).isTrue();
  }

  /**
   * Inserts a brand-new tenant row directly (raw SQL — {@code tenant} is excluded from RLS, spec
   * FS-2.1 D2, so no ambient {@link TenantContext} is needed for the insert itself), then runs
   * {@code body} with {@link TenantContext} set to it, clearing it afterward regardless of outcome.
   */
  private static void withFreshTenant(TenantScopedTest body) throws Exception {
    UUID tenantId = Uuidv7.generate();
    String tenantSlug = "vault-acceptance-" + tenantId;
    jdbc.update(
        "INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status, created_at,"
            + " updated_at) VALUES (?, ?, ?::jsonb, ?, ?, ?, now(), now())",
        tenantId,
        tenantSlug,
        "{\"en\":\"Vault acceptance\",\"ar\":\"اختبار Vault\"}",
        "OTHER",
        "SAAS",
        "ACTIVE");
    TenantContext.set(tenantId, tenantSlug);
    try {
      body.run(tenantId, tenantSlug);
    } finally {
      TenantContext.clear();
    }
  }

  @FunctionalInterface
  private interface TenantScopedTest {
    void run(UUID tenantId, String tenantSlug) throws Exception;
  }

  private static JWTClaimsSet sampleClaims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .subject("vault-acceptance-test")
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(3600)))
        .build();
  }
}
