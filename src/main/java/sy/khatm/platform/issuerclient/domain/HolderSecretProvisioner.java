package sy.khatm.platform.issuerclient.domain;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.IntegrityException;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

/**
 * Provisions the tenant-level holder HMAC secret (spec FS-2.7a D9) — the shared secret a connector
 * uses to compute {@code holderRef = HMAC-SHA256(secret, nationalId)} so the national ID itself
 * never reaches the platform.
 *
 * <p>The secret is generated once per <em>root</em> tenant (the administrative top of the KH-2.6
 * hierarchy), written to Vault KV v2 at {@code <kv-path>/<root-slug>/holder-hmac}, and shown to the
 * operator exactly once. Child tenants inherit it and never generate their own. The platform never
 * uses the secret in any computation — Vault is only the durable home, so rotation and recovery
 * have one authority.
 *
 * <p><b>Once-only, race-safe:</b> the write uses KV v2 check-and-set with {@code cas: 0} ("only if
 * the secret does not exist yet"), so two concurrent first-clients cannot both win: the loser gets
 * a check-and-set rejection and returns nothing.
 *
 * <p><b>Fail-closed:</b> a Vault outage or denial throws {@link ErrorCode#KH_ICL_0503} and the
 * enclosing client creation rolls back — a generated-but-unshown secret would be unrecoverable by
 * the operator. Vault is reached with the same plain-HTTP {@link RestClient} approach {@code
 * key.domain.VaultTransitProvider} uses (this codebase has no Spring Vault dependency), from the
 * same {@code khatm.keys.vault.*} address/token; that provider is module-private, so its
 * configuration is read again here rather than imported.
 */
@Component
public class HolderSecretProvisioner {

  private static final Logger log = LoggerFactory.getLogger(HolderSecretProvisioner.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final boolean enabled;
  private final String mount;
  private final String subPath;
  private final RestClient restClient;
  private final TenantDirectory tenants;
  private final AuditService audit;

  HolderSecretProvisioner(
      @Value("${khatm.keys.vault.enabled:false}") boolean enabled,
      @Value("${khatm.keys.vault.address:}") String address,
      @Value("${khatm.keys.vault.token:}") String token,
      @Value("${khatm.issuer.holder-secret.kv-path:khatm/tenants}") String kvPath,
      @Value("${khatm.keys.vault.connect-timeout:PT3S}") Duration connectTimeout,
      @Value("${khatm.keys.vault.read-timeout:PT5S}") Duration readTimeout,
      TenantDirectory tenants,
      AuditService audit) {
    this.enabled = enabled;
    this.tenants = tenants;
    this.audit = audit;
    int slash = kvPath.indexOf('/');
    this.mount = slash < 0 ? kvPath : kvPath.substring(0, slash);
    this.subPath = slash < 0 ? "" : kvPath.substring(slash + 1);
    if (enabled) {
      if (token == null || token.isBlank() || address == null || address.isBlank()) {
        throw new IllegalStateException(
            "khatm.keys.vault.enabled=true but address/token is blank — refusing to start"
                + " a holder-secret provisioner with no way to reach Vault.");
      }
      SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
      factory.setConnectTimeout((int) connectTimeout.toMillis());
      factory.setReadTimeout((int) readTimeout.toMillis());
      this.restClient =
          RestClient.builder()
              .baseUrl(address)
              .requestFactory(factory)
              .defaultHeader("X-Vault-Token", token)
              .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
              .build();
    } else {
      this.restClient = null;
    }
  }

  /**
   * Generate and store the holder secret if {@code tenantId} is a root tenant that has none yet.
   *
   * @param tenantId the tenant the new client belongs to
   * @return the secret (base64url, 32 random bytes) if — and only if — this call created it; empty
   *     for a child tenant, a root that already has one, or when Vault is not enabled
   * @throws IntegrityException {@code KH-ICL-0503} if Vault is enabled but the write failed
   */
  public Optional<String> provisionIfRoot(UUID tenantId) {
    if (!enabled) {
      log.debug("holder-secret provisioning skipped: Vault is not enabled on this deployment");
      return Optional.empty();
    }
    if (!tenants.ancestors(tenantId).isEmpty()) {
      return Optional.empty();
    }
    TenantRef root = tenants.findById(tenantId).orElseThrow();
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    String path = subPath.isEmpty() ? root.slug() : subPath + "/" + root.slug();
    try {
      restClient
          .post()
          .uri("/v1/{mount}/data/{path}/holder-hmac", mount, path)
          .body(Map.of("options", Map.of("cas", 0), "data", Map.of("secret", secret)))
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == 400
          && e.getResponseBodyAsString().contains("check-and-set")) {
        log.debug("holder secret already exists for root tenant {}", root.slug());
        return Optional.empty();
      }
      throw unavailable(root.slug(), e);
    } catch (RestClientException e) {
      throw unavailable(root.slug(), e);
    }
    audit.record(
        AuditAction.HOLDER_SECRET_GENERATED,
        "tenant",
        root.slug(),
        Map.of("rootSlug", root.slug()));
    log.info("holder secret generated for root tenant {}", root.slug());
    return Optional.of(secret);
  }

  private static IntegrityException unavailable(String rootSlug, Exception cause) {
    log.error(
        "holder-secret write to Vault KV failed for root tenant {} ({})",
        rootSlug,
        cause.getClass().getSimpleName());
    IntegrityException wrapped =
        new IntegrityException(ErrorCode.KH_ICL_0503, "issuer-client.holder-secret-unavailable");
    wrapped.initCause(cause);
    return wrapped;
  }
}
