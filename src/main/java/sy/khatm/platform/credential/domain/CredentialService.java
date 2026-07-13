package sy.khatm.platform.credential.domain;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.api.ConsumeRequest;
import sy.khatm.platform.credential.api.ConsumeResponse;
import sy.khatm.platform.credential.api.CredentialView;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.api.VerifyResponse;
import sy.khatm.platform.credential.persistence.ConsumptionEventRepository;
import sy.khatm.platform.credential.persistence.CredentialRepository;
import sy.khatm.platform.key.api.KeySigner;

/**
 * Core credential lifecycle service.
 *
 * <p>This class is module-private. The web layer and seed within the same module may reference it
 * directly; external modules must wait for the {@code CredentialIssuer} API interface (KH-1.x).
 *
 * <p>The atomic-consume invariant is enforced by {@link CredentialRepository#consumeOne(UUID)}: a
 * single UPDATE statement with all eligibility conditions in the WHERE clause ensures exactly one
 * concurrent caller wins.
 */
@Service
public class CredentialService {

  private final CredentialRepository credentials;
  private final ConsumptionEventRepository events;
  private final KeySigner keys;
  private final StringRedisTemplate redis;

  @Value("${khatm.issuer-did:did:web:khatm.sy:demo}")
  private String issuerDid;

  public CredentialService(
      CredentialRepository credentials,
      ConsumptionEventRepository events,
      KeySigner keys,
      StringRedisTemplate redis) {
    this.credentials = credentials;
    this.events = events;
    this.keys = keys;
    this.redis = redis;
  }

  // ── Issue ────────────────────────────────────────────────────────────────

  @Transactional
  public IssueResponse issue(IssueRequest req) throws JOSEException {
    UUID id = UUID.randomUUID();
    int maxUses = req.maxUses() == null ? 1 : req.maxUses();
    int validMinutes = req.validMinutes() == null ? 60 : req.validMinutes();
    String schemaCode = req.schemaCode() == null ? "GenericDocument/v1" : req.schemaCode();

    Instant now = Instant.now();
    Instant exp = now.plus(Duration.ofMinutes(validMinutes));
    int statusIdx = ThreadLocalRandom.current().nextInt(1, 1_000_000);
    String ref = buildRef(schemaCode);

    JWTClaimsSet.Builder claimsBuilder =
        new JWTClaimsSet.Builder()
            .issuer(issuerDid)
            .subject(req.holderRef() == null ? "holder-demo" : req.holderRef())
            .claim("ref", ref)
            .claim("vct", schemaCode)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .claim("use", Map.of("max", maxUses))
            .claim("status", Map.of("idx", statusIdx));

    if (req.claims() != null) {
      req.claims().forEach(claimsBuilder::claim);
    }

    String jwt = keys.sign(claimsBuilder.build());

    Credential c = new Credential();
    c.setId(id);
    c.setRef(ref);
    c.setSchemaCode(schemaCode);
    c.setHolderRef(req.holderRef());
    c.setSignedJwt(jwt);
    c.setValidFrom(now);
    c.setValidTo(exp);
    c.setMaxUses(maxUses);
    c.setUsesRemaining(maxUses);
    c.setStatusIdx(statusIdx);
    c.setRevoked(false);
    c.setCreatedAt(now);
    credentials.save(c);

    return new IssueResponse(id.toString(), ref, jwt);
  }

  // ── Verify (online: signature + status) ──────────────────────────────────

  @Transactional(readOnly = true)
  public VerifyResponse verify(String token) {
    SignedJWT parsed;
    JWTClaimsSet claimsSet;
    try {
      parsed = SignedJWT.parse(token);
      claimsSet = parsed.getJWTClaimsSet();
    } catch (ParseException e) {
      return new VerifyResponse(false, "malformed_token", null, null, false);
    }

    if (!keys.verifySignature(parsed)) {
      return new VerifyResponse(false, "bad_signature", null, null, false);
    }

    Map<String, Object> claims = claimsSet.getClaims();

    Date exp = claimsSet.getExpirationTime();
    if (exp != null && exp.toInstant().isBefore(Instant.now())) {
      return new VerifyResponse(false, "expired", claims, null, false);
    }

    String ref = (String) claims.get("ref");
    Optional<Credential> maybe = ref == null ? Optional.empty() : credentials.findByRef(ref);
    if (maybe.isEmpty()) {
      // Signature valid but we have no local record — still authentic offline.
      return new VerifyResponse(true, "valid_signature_unknown_ref", claims, null, false);
    }
    Credential c = maybe.get();
    if (c.isRevoked()) {
      return new VerifyResponse(false, "revoked", claims, c.getUsesRemaining(), true);
    }
    return new VerifyResponse(true, "valid", claims, c.getUsesRemaining(), false);
  }

  // ── Consume (atomic — the double-spend guard) ─────────────────────────────

  @Transactional
  public ConsumeResponse consume(ConsumeRequest req) {
    UUID id;
    try {
      id = UUID.fromString(req.id());
    } catch (IllegalArgumentException e) {
      return new ConsumeResponse(false, "bad_id", null);
    }

    String idemKey = req.idempotencyKey();
    if (idemKey != null && !idemKey.isBlank()) {
      String cached = safeRedisGet("consume:idem:" + idemKey);
      if (cached != null) {
        return new ConsumeResponse(cached.startsWith("OK"), "idempotent_replay", null);
      }
    }

    // Single-statement atomic decrement: 1 = consumed, 0 = rejected.
    int updated = credentials.consumeOne(id);
    boolean consumed = updated == 1;

    if (consumed) {
      String consumer = req.consumer() == null ? "unknown-consumer" : req.consumer();
      events.save(new ConsumptionEvent(id, consumer, "online"));
    }

    if (idemKey != null && !idemKey.isBlank()) {
      safeRedisSet("consume:idem:" + idemKey, consumed ? "OK" : "NO", Duration.ofHours(1));
    }

    Integer remaining = credentials.findById(id).map(Credential::getUsesRemaining).orElse(null);
    String reason =
        consumed
            ? "consumed"
            : (remaining != null && remaining <= 0 ? "already_consumed" : "not_consumable");
    return new ConsumeResponse(consumed, reason, remaining);
  }

  // ── Revoke ────────────────────────────────────────────────────────────────

  @Transactional
  public boolean revoke(UUID id) {
    Optional<Credential> maybe = credentials.findById(id);
    if (maybe.isEmpty()) {
      return false;
    }
    Credential c = maybe.get();
    c.setRevoked(true);
    credentials.save(c);
    return true;
  }

  public Optional<CredentialView> getView(UUID id) {
    return credentials.findById(id).map(CredentialService::toView);
  }

  private static CredentialView toView(Credential c) {
    return new CredentialView(
        c.getId().toString(),
        c.getRef(),
        c.getSchemaCode(),
        c.getUsesRemaining(),
        c.getMaxUses(),
        c.isRevoked(),
        c.getValidTo(),
        c.getSignedJwt());
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private String buildRef(String schemaCode) {
    String prefix = schemaCode.replaceAll("[^A-Za-z]", "");
    prefix = prefix.length() >= 3 ? prefix.substring(0, 3).toUpperCase() : "DOC";
    int n = ThreadLocalRandom.current().nextInt(100000, 999999);
    return prefix + "-" + java.time.Year.now() + "-" + n;
  }

  private String safeRedisGet(String key) {
    try {
      return redis.opsForValue().get(key);
    } catch (Exception e) {
      return null;
    }
  }

  private void safeRedisSet(String key, String val, Duration ttl) {
    try {
      redis.opsForValue().set(key, val, ttl);
    } catch (Exception ignored) {
      // Redis is best-effort for idempotency cache; failure degrades to non-idempotent.
    }
  }
}
