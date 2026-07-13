package sy.khatm.platform.credential.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.consumer.api.ConsumingPartyRef;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.credential.api.ConsumeRequest;
import sy.khatm.platform.credential.api.ConsumeResponse;
import sy.khatm.platform.credential.api.CredentialView;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.api.VerifyResponse;
import sy.khatm.platform.credential.persistence.ClaimCodeRepository;
import sy.khatm.platform.credential.persistence.ConsumptionEventRepository;
import sy.khatm.platform.credential.persistence.CredentialRepository;
import sy.khatm.platform.holder.api.HolderDirectory;
import sy.khatm.platform.holder.api.HolderRef;
import sy.khatm.platform.key.api.KeySigner;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaDefinition;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.status.api.StatusAllocation;
import sy.khatm.platform.status.api.StatusListAllocator;

/**
 * Core credential lifecycle service.
 *
 * <p>This class is module-private. The web layer and seed within the same module may reference it
 * directly; external modules must wait for the {@code CredentialIssuer} API interface (KH-1.x).
 *
 * <p>The atomic-consume invariant is enforced by {@link CredentialRepository#consumeOne(UUID)}: a
 * single UPDATE statement with all eligibility conditions in the WHERE clause ensures exactly one
 * concurrent caller wins.
 *
 * <p>Issuing a credential now orchestrates three other modules' APIs to satisfy the baseline
 * schema's foreign keys (spec FS-0.2 §3.6): {@link SchemaCatalog} for {@code schema_id}, {@link
 * HolderDirectory} for {@code holder_id}, {@link StatusListAllocator} for {@code (status_list_id,
 * status_idx)}. All three "ensure/allocate" methods find-or-create — real console-driven onboarding
 * for schemas/holders/consuming parties is KH-1.x.
 */
@Service
public class CredentialService {

  private static final String DEFAULT_STATUS_LIST_CODE = "default";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final CredentialRepository credentials;
  private final ConsumptionEventRepository events;
  private final ClaimCodeRepository claimCodes;
  private final KeySigner keys;
  private final SchemaCatalog schemas;
  private final HolderDirectory holders;
  private final StatusListAllocator statusLists;
  private final ConsumingPartyRegistry consumingParties;
  private final StringRedisTemplate redis;

  @Value("${khatm.issuer-did:did:web:khatm.sy:demo}")
  private String issuerDid;

  public CredentialService(
      CredentialRepository credentials,
      ConsumptionEventRepository events,
      ClaimCodeRepository claimCodes,
      KeySigner keys,
      SchemaCatalog schemas,
      HolderDirectory holders,
      StatusListAllocator statusLists,
      ConsumingPartyRegistry consumingParties,
      StringRedisTemplate redis) {
    this.credentials = credentials;
    this.events = events;
    this.claimCodes = claimCodes;
    this.keys = keys;
    this.schemas = schemas;
    this.holders = holders;
    this.statusLists = statusLists;
    this.consumingParties = consumingParties;
    this.redis = redis;
  }

  // ── Issue ────────────────────────────────────────────────────────────────

  @Transactional
  public IssueResponse issue(IssueRequest req) throws JOSEException {
    UUID tenantId = TenantContext.current();
    UUID id = Uuidv7.generate();
    int maxUses = req.maxUses() == null ? 1 : req.maxUses();
    int validMinutes = req.validMinutes() == null ? 60 : req.validMinutes();
    String schemaCode = req.schemaCode() == null ? "GenericDocument/v1" : req.schemaCode();
    String holderPseudoRef = req.holderRef() == null ? "holder-demo" : req.holderRef();
    Map<String, Object> claims = req.claims() == null ? Map.of() : req.claims();

    SchemaRef schemaRef =
        schemas.ensurePublished(buildSchemaDefinition(schemaCode, claims, maxUses));
    HolderRef holderRef = holders.ensureHolder(holderPseudoRef);
    StatusAllocation allocation = statusLists.allocate(DEFAULT_STATUS_LIST_CODE);

    Instant now = Instant.now();
    Instant exp = now.plus(Duration.ofMinutes(validMinutes));
    String ref = buildRef(schemaCode);

    JWTClaimsSet.Builder claimsBuilder =
        new JWTClaimsSet.Builder()
            .issuer(issuerDid)
            .subject(holderPseudoRef)
            .claim("ref", ref)
            .claim("vct", schemaCode)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .claim("use", Map.of("max", maxUses))
            .claim("status", Map.of("idx", allocation.idx()));
    claims.forEach(claimsBuilder::claim);

    String jwt = keys.sign(claimsBuilder.build());

    Credential c = new Credential();
    c.setId(id);
    c.setTenantId(tenantId);
    c.setSchemaId(schemaRef.id());
    c.setHolderId(holderRef.id());
    c.setRef(ref);
    c.setSignedPayload(jwt);
    c.setPayloadHash(sha256(jwt));
    c.setStatusListId(allocation.statusListId());
    c.setStatusIdx(allocation.idx());
    c.setValidFrom(now);
    c.setValidTo(exp);
    c.setMaxUses(maxUses);
    c.setUsesRemaining(maxUses);
    c.setRevoked(false);
    c.setCreatedAt(now);
    credentials.save(c);

    return new IssueResponse(id.toString(), ref, jwt);
  }

  /**
   * Issue a one-time wallet claim code for an already-issued credential (spec FS-0.2 §3.7).
   *
   * <p>Encrypting real disclosure values into {@code disclosures_enc} is KH-1.2.1; this method
   * leaves that column {@code null} so the row shape is exercised ahead of that work.
   *
   * @param credentialId the credential to generate a claim code for
   * @param ttl how long the code remains claimable
   * @return the raw one-time code (shown to the caller exactly once) and its expiry
   */
  @Transactional
  public ClaimCodeIssued issueClaimCode(UUID credentialId, Duration ttl) {
    byte[] codeBytes = new byte[16];
    SECURE_RANDOM.nextBytes(codeBytes);
    String code = HexFormat.of().formatHex(codeBytes);
    Instant expiresAt = Instant.now().plus(ttl);

    ClaimCode claimCode = new ClaimCode();
    claimCode.setId(Uuidv7.generate());
    claimCode.setTenantId(TenantContext.current());
    claimCode.setCredentialId(credentialId);
    claimCode.setCodeHash(sha256(code));
    claimCode.setExpiresAt(expiresAt);
    claimCode.setCreatedAt(Instant.now());
    claimCodes.save(claimCode);

    return new ClaimCodeIssued(code, expiresAt);
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

    String callerIdemKey = req.idempotencyKey();
    boolean callerWantsIdempotency = callerIdemKey != null && !callerIdemKey.isBlank();
    if (callerWantsIdempotency) {
      String cached = safeRedisGet("consume:idem:" + callerIdemKey);
      if (cached != null) {
        return new ConsumeResponse(cached.startsWith("OK"), "idempotent_replay", null);
      }
    }

    // Single-statement atomic decrement: 1 = consumed, 0 = rejected.
    int updated = credentials.consumeOne(id);
    boolean consumed = updated == 1;

    if (consumed) {
      String consumerCode = req.consumer() == null ? "unknown-consumer" : req.consumer();
      ConsumingPartyRef party = consumingParties.ensure(consumerCode);
      // idempotency_key is NOT NULL + UNIQUE in the baseline schema (the durable fallback for
      // the double-submit guard); callers who don't supply one still get a unique row.
      String eventIdemKey = callerWantsIdempotency ? callerIdemKey : Uuidv7.generate().toString();
      events.save(
          new ConsumptionEvent(TenantContext.current(), id, party.id(), eventIdemKey, "ONLINE"));
    }

    if (callerWantsIdempotency) {
      safeRedisSet("consume:idem:" + callerIdemKey, consumed ? "OK" : "NO", Duration.ofHours(1));
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
    c.setRevokedAt(Instant.now());
    credentials.save(c);
    return true;
  }

  public Optional<CredentialView> getView(UUID id) {
    return credentials.findById(id).map(this::toView);
  }

  private CredentialView toView(Credential c) {
    String schemaCode = schemas.findById(c.getSchemaId()).map(SchemaRef::code).orElse(null);
    return new CredentialView(
        c.getId().toString(),
        c.getRef(),
        schemaCode,
        c.getUsesRemaining(),
        c.getMaxUses(),
        c.isRevoked(),
        c.getValidTo(),
        c.getSignedPayload());
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private String buildRef(String schemaCode) {
    String prefix = schemaCode.replaceAll("[^A-Za-z]", "");
    prefix = prefix.length() >= 3 ? prefix.substring(0, 3).toUpperCase() : "DOC";
    int n = ThreadLocalRandom.current().nextInt(100000, 999999);
    return prefix + "-" + java.time.Year.now() + "-" + n;
  }

  /**
   * Build a minimal claims-field definition from the claim keys supplied at issue time.
   *
   * <p>Real schema authoring with a typed claims editor is KH-1.x; this exists only so that {@link
   * SchemaCatalog#ensurePublished} has a non-null {@code claims_def} to persist for schemas created
   * on the fly (e.g. by the demo seeder).
   */
  private static SchemaDefinition buildSchemaDefinition(
      String schemaCode, Map<String, Object> claims, int maxUses) {
    ObjectNode claimsDef = JSON.createObjectNode();
    for (String field : claims.keySet()) {
      ObjectNode fieldDef = claimsDef.putObject(field);
      fieldDef.put("type", "string");
      fieldDef.put("required", false);
      ObjectNode label = fieldDef.putObject("label_i18n");
      label.put("en", field);
      label.put("ar", field);
    }
    return new SchemaDefinition(
        schemaCode,
        1,
        new LocalizedText(schemaCode, schemaCode),
        claimsDef.toString(),
        List.copyOf(claims.keySet()),
        maxUses);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm", e);
    }
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
