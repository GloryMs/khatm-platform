package sy.khatm.platform.issuerclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;
import sy.khatm.platform.KhatmPlatformApplication;
import sy.khatm.platform.issuerclient.domain.CreatedIssuerClient;
import sy.khatm.platform.issuerclient.domain.IssuerClientLifecycle;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;

/**
 * KH-2.8.1 (spec FS-2.7a D9/V3) against a REAL Vault with KV v2 mounted at {@code khatm/}: the
 * first client of a root tenant generates the holder HMAC secret in {@code
 * khatm/data/tenants/<root>/holder-hmac} and returns it once; a second client of the same root, and
 * any client of a child tenant, neither writes nor returns one; concurrent first clients still
 * yield exactly one secret (KV check-and-set); and the audit row is written exactly once.
 */
@Testcontainers
class HolderSecretVaultAcceptanceTest {

  private static final String VAULT_TOKEN = "holder-secret-test-root-token";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("db/khatm-app-role-init.sql");

  @Container
  static final VaultContainer<?> VAULT =
      new VaultContainer<>(DockerImageName.parse("hashicorp/vault:1.17"))
          .withVaultToken(VAULT_TOKEN)
          .withInitCommand("secrets enable transit", "secrets enable -path=khatm kv-v2");

  @TempDir private static Path tempDir;

  private static ConfigurableApplicationContext context;
  private static IssuerClientLifecycle lifecycle;
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
                "--khatm.keys.soft.keystore-path=" + tempDir.resolve("holder-secret-keys.p12"),
                "--khatm.keys.soft.passphrase=holder-secret-test-passphrase",
                "--khatm.keys.vault.enabled=true",
                "--khatm.keys.vault.address=" + VAULT.getHttpHostAddress(),
                "--khatm.keys.vault.token=" + VAULT_TOKEN,
                "--khatm.claims.enc-key=a2hhdG0tdGVzdC1jbGFpbXMtZW5jLWtleS0zMmJ5dGU=",
                "--khatm.auth.totp.enc-key=a2hhdG0tdGVzdC10b3RwLWVuYy1rZXktMzJieXRlcyE=",
                "--khatm.auth.bootstrap.admin-username=test-admin",
                "--khatm.auth.bootstrap.admin-password=test-admin-password-change-me",
                "--khatm.public-base-url=http://localhost:8080");
    lifecycle = context.getBean(IssuerClientLifecycle.class);
    jdbc = context.getBean(JdbcTemplate.class);
  }

  @AfterAll
  static void stopContext() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void firstClientOfARoot_generatesTheSecretInVault_andReturnsItOnce() throws Exception {
    UUID root = insertTenant("hs-root-" + UUID.randomUUID(), null);
    String slug = slugOf(root);

    CreatedIssuerClient first = createIn(root, slug);
    CreatedIssuerClient second = createIn(root, slug);

    assertThat(first.holderHmacSecret()).isNotBlank().hasSize(43);
    assertThat(second.holderHmacSecret())
        .as("a second client of the same root neither generates nor returns a secret")
        .isNull();
    assertThat(readFromVault(slug))
        .as("Vault holds exactly the secret that was shown once")
        .isEqualTo(first.holderHmacSecret());
    assertThat(generatedAuditRows(root, slug)).isEqualTo(1);
  }

  @Test
  void aChildTenantsClient_inheritsTheRootSecret_neverGeneratingItsOwn() throws Exception {
    UUID root = insertTenant("hs-parent-" + UUID.randomUUID(), null);
    UUID child = insertTenant("hs-child-" + UUID.randomUUID(), root);
    String childSlug = slugOf(child);

    CreatedIssuerClient created = createIn(child, childSlug);

    assertThat(created.holderHmacSecret()).isNull();
    ExecResult result = readVaultRaw(childSlug);
    assertThat(result.getExitCode())
        .as("nothing may exist in KV for a child tenant: %s", result.getStderr())
        .isNotZero();
    assertThat(generatedAuditRows(child, childSlug)).isZero();
  }

  @Test
  void concurrentFirstClients_yieldExactlyOneSecret() throws Exception {
    UUID root = insertTenant("hs-race-" + UUID.randomUUID(), null);
    String slug = slugOf(root);
    int contenders = 6;
    ExecutorService pool = Executors.newFixedThreadPool(contenders);
    CountDownLatch ready = new CountDownLatch(contenders);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<String>> results = new ArrayList<>();
    for (int i = 0; i < contenders; i++) {
      results.add(
          pool.submit(
              () -> {
                ready.countDown();
                start.await();
                return createIn(root, slug).holderHmacSecret();
              }));
    }
    ready.await();
    start.countDown();
    List<String> secrets = new ArrayList<>();
    for (Future<String> f : results) {
      secrets.add(f.get(60, TimeUnit.SECONDS));
    }
    pool.shutdownNow();

    assertThat(secrets.stream().filter(s -> s != null).toList())
        .as("KV check-and-set lets exactly one contender generate the secret")
        .hasSize(1);
    assertThat(generatedAuditRows(root, slug)).isEqualTo(1);
    assertThat(readFromVault(slug))
        .isEqualTo(secrets.stream().filter(s -> s != null).findFirst().get());
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────

  private static CreatedIssuerClient createIn(UUID tenantId, String slug) {
    TenantContext.set(tenantId, slug);
    try {
      return lifecycle.create(new LocalizedText("Connector", "موصِّل"), List.of(), null);
    } finally {
      TenantContext.clear();
    }
  }

  private static UUID insertTenant(String slug, UUID parent) {
    UUID id = Uuidv7.generate();
    jdbc.update(
        "INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status, parent_tenant_id,"
            + " created_at, updated_at) VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, now(), now())",
        id,
        slug,
        "{\"en\":\"Holder secret\",\"ar\":\"سر الحامل\"}",
        "OTHER",
        "SAAS",
        "ACTIVE",
        parent);
    return id;
  }

  private static String slugOf(UUID tenantId) {
    return jdbc.queryForObject("SELECT slug FROM tenant WHERE id = ?", String.class, tenantId);
  }

  private static int generatedAuditRows(UUID tenantId, String slug) {
    TenantContext.set(tenantId, slug);
    try {
      return jdbc.queryForObject(
          "SELECT COUNT(*) FROM audit_log WHERE action = 'HOLDER_SECRET_GENERATED'"
              + " AND entity_ref = ?",
          Integer.class,
          slug);
    } finally {
      TenantContext.clear();
    }
  }

  private static String readFromVault(String slug) throws Exception {
    ExecResult result = readVaultRaw(slug);
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    return result.getStdout().trim();
  }

  private static ExecResult readVaultRaw(String slug) throws Exception {
    return VAULT.execInContainer(
        "sh",
        "-c",
        "VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN="
            + VAULT_TOKEN
            + " vault kv get -mount=khatm -field=secret tenants/"
            + slug
            + "/holder-hmac");
  }
}
