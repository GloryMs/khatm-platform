package sy.khatm.platform.rbac.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.rbac.api.CurrentActor;
import sy.khatm.platform.rbac.persistence.ApiKeyRepository;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Create, revoke, and verify API keys (spec FS-0.6b §4, D2–D4).
 *
 * <p><b>Key shape (D2):</b> {@code khk_<env>_<prefix>.<secret>} — {@code env} is {@code test} when
 * the {@code local}, {@code dev}, or {@code test} Spring profile is active, {@code live} otherwise;
 * derived from the active profile rather than a dedicated config key, since spec FS-0.6b §7's
 * config surface is exhaustive and does not list one. {@code prefix} is 8 base62 characters, stored
 * in the clear as the lookup column (support can identify a key from the first line of a log
 * without ever seeing the secret); {@code secret} is 32 base62 characters (~190 bits of {@link
 * SecureRandom} entropy).
 *
 * <p><b>Hashing (D4):</b> SHA-256 of the secret, not a slow password hash — the secret's own
 * entropy already makes brute-force infeasible, so a fast hash keeps every API-key-authenticated
 * request cheap. This is the opposite trade-off from {@link AuthService}'s human passwords, which
 * use argon2id specifically because human-chosen passwords have far less entropy.
 *
 * <p>This class is module-private; {@code rbac.web.AuthController}'s admin endpoints are the only
 * caller (a different Java package inside the module-private {@code rbac} module — see {@link
 * LoginResult}'s Javadoc for why the types this method returns are {@code public}).
 */
@Service
public class ApiKeyService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final String BASE62 =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final int PREFIX_LENGTH = 8;
  private static final int SECRET_LENGTH = 32;
  private static final String KEY_PREFIX_TAG = "khk_";

  private final ApiKeyRepository repository;
  private final AuditService audit;
  private final String env;

  public ApiKeyService(ApiKeyRepository repository, AuditService audit, Environment environment) {
    this.repository = repository;
    this.audit = audit;
    this.env = environment.acceptsProfiles(Profiles.of("local", "dev", "test")) ? "test" : "live";
  }

  /**
   * Create a new API key. The returned {@link CreatedApiKey#rawKey} is the only time the secret is
   * ever available — the platform stores only its SHA-256 hash (D4).
   *
   * @param ownerType who this key acts on behalf of
   * @param ownerId the owning consuming party's id ({@code CONSUMING_PARTY} only); must be {@code
   *     null} for {@code TENANT}
   * @param scopes the scopes to grant this key (a subset of the platform's scope catalog)
   * @return the created key's id, prefix, and one-time raw value
   */
  @Transactional
  public CreatedApiKey create(ApiKeyOwnerType ownerType, UUID ownerId, Set<String> scopes) {
    String prefix = randomBase62(PREFIX_LENGTH);
    String secret = randomBase62(SECRET_LENGTH);

    ApiKey key = new ApiKey();
    key.setId(Uuidv7.generate());
    key.setTenantId(TenantContext.current());
    key.setOwnerType(ownerType.name());
    key.setOwnerId(ownerId);
    key.setKeyPrefix(prefix);
    key.setKeyHash(sha256(secret));
    key.setScopes(scopes.toArray(new String[0]));
    key.setStatus(ApiKey.STATUS_ACTIVE);
    key.setCreatedAt(Instant.now());
    repository.save(key);

    audit.record(AuditAction.API_KEY_CREATED, "api_key", prefix, null);
    return new CreatedApiKey(
        key.getId(), prefix, KEY_PREFIX_TAG + env + "_" + prefix + "." + secret);
  }

  /**
   * Revoke an API key — it stops authenticating on the very next request (spec FS-0.6b DoD #5).
   *
   * @param id the key to revoke
   * @return {@code true} if a key with this id existed and was revoked, {@code false} otherwise
   */
  @Transactional
  public boolean revoke(UUID id) {
    Optional<ApiKey> maybe = repository.findById(id);
    if (maybe.isEmpty()) {
      return false;
    }
    ApiKey key = maybe.get();
    key.setStatus(ApiKey.STATUS_REVOKED);
    key.setRevokedAt(Instant.now());
    repository.save(key);
    audit.record(AuditAction.API_KEY_REVOKED, "api_key", key.getKeyPrefix(), null);
    return true;
  }

  /**
   * Verify a raw {@code Authorization: Bearer} value against the {@code api_key} table.
   *
   * <p>Never throws and never audits by itself — {@code rbac.security.ApiKeyAuthFilter} is the
   * single place that records {@code API_KEY_AUTH_FAILED} (spec FS-0.6b §6), so it can attach
   * whatever best-effort {@code detail.prefix} it can parse regardless of why verification failed.
   *
   * @param rawKey the full {@code khk_<env>_<prefix>.<secret>} value
   * @return the verified key's identity, or empty if the value is malformed, unknown, revoked, or
   *     the secret does not match
   */
  @Transactional
  public Optional<VerifiedApiKey> verify(String rawKey) {
    Optional<String[]> parsed = parse(rawKey);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    String prefix = parsed.get()[0];
    String secret = parsed.get()[1];

    Optional<ApiKey> maybeKey = repository.findByKeyPrefix(prefix);
    if (maybeKey.isEmpty()) {
      return Optional.empty();
    }
    ApiKey key = maybeKey.get();
    if (!key.isActive()) {
      return Optional.empty();
    }
    if (!MessageDigest.isEqual(sha256(secret), key.getKeyHash())) {
      return Optional.empty();
    }

    key.setLastUsedAt(Instant.now());
    repository.save(key);

    CurrentActor.ActorKind kind =
        key.isConsumingParty()
            ? CurrentActor.ActorKind.API_KEY_CONSUMING_PARTY
            : CurrentActor.ActorKind.API_KEY_TENANT;
    return Optional.of(
        new VerifiedApiKey(
            key.getId(),
            key.getTenantId(),
            kind,
            key.getOwnerId(),
            new LinkedHashSet<>(Arrays.asList(key.getScopes()))));
  }

  /**
   * Best-effort extraction of the {@code prefix} segment from a raw key value, tolerant of any
   * malformation — used only to populate {@code API_KEY_AUTH_FAILED}'s {@code detail.prefix} (never
   * the secret) when {@link #verify} rejects a key for any reason.
   *
   * @param rawKey the raw {@code Authorization} header value (with or without the {@code khk_}
   *     shape)
   * @return the parsed prefix, or empty if the value doesn't even structurally resemble a key
   */
  public static Optional<String> extractPrefixForLogging(String rawKey) {
    return parse(rawKey).map(parts -> parts[0]);
  }

  private static Optional<String[]> parse(String rawKey) {
    if (rawKey == null || !rawKey.startsWith(KEY_PREFIX_TAG)) {
      return Optional.empty();
    }
    String rest = rawKey.substring(KEY_PREFIX_TAG.length());
    int underscoreIdx = rest.indexOf('_');
    int dotIdx = rest.indexOf('.');
    if (underscoreIdx < 0 || dotIdx < 0 || dotIdx < underscoreIdx) {
      return Optional.empty();
    }
    String prefix = rest.substring(underscoreIdx + 1, dotIdx);
    String secret = rest.substring(dotIdx + 1);
    if (prefix.isEmpty() || secret.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new String[] {prefix, secret});
  }

  private static String randomBase62(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(BASE62.charAt(SECURE_RANDOM.nextInt(BASE62.length())));
    }
    return sb.toString();
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm", e);
    }
  }
}
