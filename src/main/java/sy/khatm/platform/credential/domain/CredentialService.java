package sy.khatm.platform.credential.domain;

import com.authlete.sd.Disclosure;
import com.authlete.sd.SDJWT;
import com.authlete.sd.SDObjectBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
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
import sy.khatm.platform.key.api.KeyVerifier;
import sy.khatm.platform.key.api.PublicKeyHandle;
import sy.khatm.platform.key.api.SignResult;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaDefinition;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.IntegrityException;
import sy.khatm.platform.shared.error.VerifyReason;
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
 *
 * <p><b>SD-JWT (spec FS-0.4):</b> every {@link IssueRequest#claims} entry becomes a salted {@link
 * Disclosure} (D1) — {@code credential.signed_payload} stores only the compact JWT (digests + D3
 * structural fields), never a disclosed value. {@link #issue} returns the full tilde-separated
 * presentation as a one-time delivery; {@link #verify} accepts that same presentation format (or a
 * bare compact JWT, treated as a zero-disclosure presentation per spec §5) and enforces every D8
 * rejection plus the D2 mandatory-disclosure check.
 */
@Service
public class CredentialService {

  private static final String DEFAULT_STATUS_LIST_CODE = "default";
  private static final String SD_ALG = "sha-256";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final CredentialRepository credentials;
  private final ConsumptionEventRepository events;
  private final ClaimCodeRepository claimCodes;
  private final KeySigner keys;
  private final KeyVerifier keyVerifier;
  private final SchemaCatalog schemas;
  private final HolderDirectory holders;
  private final StatusListAllocator statusLists;
  private final ConsumingPartyRegistry consumingParties;
  private final StringRedisTemplate redis;
  private final CredentialMapper mapper;
  private final ClaimsEncryptionService claimsEncryption;

  @Value("${khatm.issuer-did:did:web:khatm.sy:demo}")
  private String issuerDid;

  public CredentialService(
      CredentialRepository credentials,
      ConsumptionEventRepository events,
      ClaimCodeRepository claimCodes,
      KeySigner keys,
      KeyVerifier keyVerifier,
      SchemaCatalog schemas,
      HolderDirectory holders,
      StatusListAllocator statusLists,
      ConsumingPartyRegistry consumingParties,
      StringRedisTemplate redis,
      CredentialMapper mapper,
      ClaimsEncryptionService claimsEncryption) {
    this.credentials = credentials;
    this.events = events;
    this.claimCodes = claimCodes;
    this.keys = keys;
    this.keyVerifier = keyVerifier;
    this.schemas = schemas;
    this.holders = holders;
    this.statusLists = statusLists;
    this.consumingParties = consumingParties;
    this.redis = redis;
    this.mapper = mapper;
    this.claimsEncryption = claimsEncryption;
  }

  // ── Issue ────────────────────────────────────────────────────────────────

  @Transactional
  public IssueResponse issue(IssueRequest req) {
    UUID tenantId = TenantContext.current();
    UUID id = Uuidv7.generate();
    int maxUses = req.maxUses() == null ? 1 : req.maxUses();
    int validMinutes = req.validMinutes() == null ? 60 : req.validMinutes();
    String schemaCode = req.schemaCode() == null ? "GenericDocument/v1" : req.schemaCode();
    String holderPseudoRef = req.holderRef() == null ? "holder-demo" : req.holderRef();
    Map<String, Object> claims = req.claims() == null ? Map.of() : req.claims();
    List<String> sdFields = req.sdFields() == null ? List.copyOf(claims.keySet()) : req.sdFields();

    SchemaRef schemaRef =
        schemas.ensurePublished(buildSchemaDefinition(schemaCode, claims, sdFields, maxUses));
    HolderRef holderRef = holders.ensureHolder(holderPseudoRef);
    StatusAllocation allocation = statusLists.allocate(DEFAULT_STATUS_LIST_CODE);

    Instant now = Instant.now();
    Instant exp = now.plus(Duration.ofMinutes(validMinutes));
    String ref = buildRef(schemaCode);
    String vct = schemaRef.code() + ":" + schemaRef.version();

    // D1: every claim becomes a salted disclosure — no business claim ever appears explicitly
    // in the payload we sign, so P1 is a property of the token's structure, not just policy.
    SDObjectBuilder sdBuilder = new SDObjectBuilder();
    List<Disclosure> disclosures = new ArrayList<>();
    for (Map.Entry<String, Object> entry : claims.entrySet()) {
      disclosures.add(sdBuilder.putSDClaim(entry.getKey(), entry.getValue()));
    }
    // D3: the only claims that ever appear explicitly.
    sdBuilder.putClaim("iss", issuerDid);
    sdBuilder.putClaim("vct", vct);
    sdBuilder.putClaim("ref", ref);
    sdBuilder.putClaim("status", statusClaim(allocation));
    sdBuilder.putClaim("iat", now.getEpochSecond());
    sdBuilder.putClaim("nbf", now.getEpochSecond());
    sdBuilder.putClaim("exp", exp.getEpochSecond());
    Map<String, Object> payload = sdBuilder.build(/* hashAlgorithmIncluded= */ true);

    JWTClaimsSet claimsSet;
    try {
      claimsSet = JWTClaimsSet.parse(payload);
    } catch (ParseException e) {
      // The map above is entirely our own construction — a parse failure here means an
      // internal invariant broke, not bad caller input.
      throw new IllegalStateException("Failed to build the SD-JWT claims set.", e);
    }

    SignResult signed;
    try {
      signed = keys.sign(claimsSet);
    } catch (JOSEException e) {
      // Signing is the one truly "our fault, not the caller's" failure in this path — an
      // internal integrity problem (key module unreachable/misconfigured), not bad input.
      // initCause (not the KhatmException constructor, which CLAUDE.md fixes to
      // (ErrorCode, messageKey, args...)) so GlobalExceptionHandler's 5xx path still logs the
      // original signing failure's full stack trace, not just this wrapper's.
      IntegrityException wrapped =
          new IntegrityException(ErrorCode.KH_KEY_0500, "key.signing-failed");
      wrapped.initCause(e);
      throw wrapped;
    }
    String compactJwt = signed.jws();
    // D6: standard tilde-separated presentation format.
    String presentation = new SDJWT(compactJwt, disclosures).toString();

    Credential c = new Credential();
    c.setId(id);
    c.setTenantId(tenantId);
    c.setSchemaId(schemaRef.id());
    c.setHolderId(holderRef.id());
    c.setRef(ref);
    // D6: signed_payload stores the compact JWT only — digests, never disclosed values.
    c.setSignedPayload(compactJwt);
    c.setPayloadHash(sha256(compactJwt));
    c.setStatusListId(allocation.statusListId());
    c.setStatusIdx(allocation.idx());
    c.setValidFrom(now);
    c.setValidTo(exp);
    c.setMaxUses(maxUses);
    c.setUsesRemaining(maxUses);
    c.setRevoked(false);
    c.setCreatedAt(now);
    credentials.save(c);

    return new IssueResponse(id.toString(), ref, presentation);
  }

  /**
   * Issue a one-time wallet claim code for an already-issued credential (spec FS-0.2 §3.7).
   *
   * <p>The disclosures are extracted from {@code sdJwtPresentation} — the exact string {@link
   * #issue} returned — and AES-256-GCM encrypted before being persisted (spec FS-0.4 D7). This is
   * why a claim code can only be created from the presentation the issuer holds right after issuing
   * (or a caller-supplied one from elsewhere); the platform never stores disclosures in plaintext
   * anywhere, even transiently, so there is no other place to source them from later.
   *
   * @param credentialId the credential to generate a claim code for
   * @param sdJwtPresentation the full SD-JWT presentation returned by {@link #issue} for this
   *     credential (or an equivalent one covering the same disclosures)
   * @param ttl how long the code remains claimable
   * @return the raw one-time code (shown to the caller exactly once) and its expiry
   */
  @Transactional
  public ClaimCodeIssued issueClaimCode(UUID credentialId, String sdJwtPresentation, Duration ttl) {
    byte[] codeBytes = new byte[16];
    SECURE_RANDOM.nextBytes(codeBytes);
    String code = HexFormat.of().formatHex(codeBytes);
    Instant expiresAt = Instant.now().plus(ttl);

    String joinedDisclosures = joinDisclosures(sdJwtPresentation);
    byte[] disclosuresEnc =
        claimsEncryption.encrypt(joinedDisclosures.getBytes(StandardCharsets.UTF_8));

    ClaimCode claimCode = new ClaimCode();
    claimCode.setId(Uuidv7.generate());
    claimCode.setTenantId(TenantContext.current());
    claimCode.setCredentialId(credentialId);
    claimCode.setCodeHash(sha256(code));
    claimCode.setDisclosuresEnc(disclosuresEnc);
    claimCode.setExpiresAt(expiresAt);
    claimCode.setCreatedAt(Instant.now());
    claimCodes.save(claimCode);

    return new ClaimCodeIssued(code, expiresAt);
  }

  // ── Verify (online: signature + status) ──────────────────────────────────

  @Transactional(readOnly = true)
  public VerifyResponse verify(String presentation) {
    if (presentation == null) {
      return result(false, VerifyReason.MALFORMED, null, null, false);
    }

    String compactJwt;
    List<Disclosure> disclosures;
    if (presentation.contains("~")) {
      SDJWT sdJwt;
      try {
        sdJwt = SDJWT.parse(presentation);
      } catch (RuntimeException e) {
        return result(false, VerifyReason.MALFORMED, null, null, false);
      }
      compactJwt = sdJwt.getCredentialJwt();
      disclosures = sdJwt.getDisclosures();
    } else {
      // spec FS-0.4 §5: a bare compact JWT with no disclosures at all is a valid
      // zero-disclosure presentation, not a malformed request — it will typically (and
      // correctly) fail the mandatory-disclosure check below instead.
      compactJwt = presentation;
      disclosures = List.of();
    }

    SignedJWT parsed;
    JWTClaimsSet claimsSet;
    try {
      parsed = SignedJWT.parse(compactJwt);
      claimsSet = parsed.getJWTClaimsSet();
    } catch (ParseException e) {
      return result(false, VerifyReason.MALFORMED, null, null, false);
    }

    VerifyReason signatureResult = checkSignature(parsed);
    if (signatureResult != VerifyReason.VALID) {
      return result(false, signatureResult, null, null, false);
    }

    Map<String, Object> rawClaims = claimsSet.getClaims();
    Map<String, Object> structural = structuralClaims(rawClaims);

    Date exp = claimsSet.getExpirationTime();
    if (exp != null && exp.toInstant().isBefore(Instant.now())) {
      return result(false, VerifyReason.EXPIRED, structural, null, false);
    }

    String ref = (String) rawClaims.get("ref");
    Optional<Credential> maybe = ref == null ? Optional.empty() : credentials.findByRef(ref);
    if (maybe.isEmpty()) {
      // Signature valid but we have no local record — still authentic offline. The
      // mandatory-disclosure check (D2) needs the issuing schema, which we only know via this
      // DB row, so an unknown ref skips straight to this existing early-exit (spec FS-0.4 §4
      // step 2 reuses this path unchanged).
      return result(true, VerifyReason.VALID_SIGNATURE_UNKNOWN_REF, structural, null, false);
    }
    Credential c = maybe.get();
    if (c.isRevoked()) {
      return result(false, VerifyReason.REVOKED, structural, c.getUsesRemaining(), true);
    }

    // D8: _sd_alg must be sha-256.
    if (!SD_ALG.equals(rawClaims.get("_sd_alg"))) {
      return result(false, VerifyReason.BAD_SD_ALG, structural, c.getUsesRemaining(), false);
    }
    List<?> sdDigests = rawClaims.get("_sd") instanceof List<?> list ? list : List.of();

    // D8: every presented disclosure's digest must be in _sd, and no duplicate claim names.
    Map<String, Object> disclosed = new LinkedHashMap<>();
    for (Disclosure d : disclosures) {
      String claimName = d.getClaimName();
      if (claimName == null || !sdDigests.contains(d.digest())) {
        return result(
            false, VerifyReason.FORGED_DISCLOSURE, structural, c.getUsesRemaining(), false);
      }
      if (disclosed.containsKey(claimName)) {
        return result(
            false, VerifyReason.DUPLICATE_DISCLOSURE, structural, c.getUsesRemaining(), false);
      }
      disclosed.put(claimName, d.getClaimValue());
    }

    // D2: every claims_def field NOT in schema.sd_fields is mandatory to disclose.
    Optional<SchemaRef> schemaRef = schemas.findById(c.getSchemaId());
    if (schemaRef.isPresent()) {
      for (String mandatoryField : mandatoryFields(schemaRef.get())) {
        if (!disclosed.containsKey(mandatoryField)) {
          return result(
              false,
              VerifyReason.WITHHELD_MANDATORY_CLAIM,
              structural,
              c.getUsesRemaining(),
              false);
        }
      }
    }

    Map<String, Object> verifiedClaims = new LinkedHashMap<>(structural);
    verifiedClaims.putAll(disclosed);
    return result(true, VerifyReason.VALID, verifiedClaims, c.getUsesRemaining(), false);
  }

  /**
   * Build a {@link VerifyResponse} carrying {@code reason}'s wire code. {@code reasonMessage} is
   * left {@code null} — localizing it needs the request locale, which is a web-layer concern; the
   * controller resolves and fills it in via {@code MessageSource} (spec FS-0.6a §3).
   */
  private static VerifyResponse result(
      boolean valid,
      VerifyReason reason,
      Map<String, Object> claims,
      Integer usesRemaining,
      boolean revoked) {
    return new VerifyResponse(valid, reason.code(), null, claims, usesRemaining, revoked);
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
    return credentials.findById(id).map(mapper::toView);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private String buildRef(String schemaCode) {
    String prefix = schemaCode.replaceAll("[^A-Za-z]", "");
    prefix = prefix.length() >= 3 ? prefix.substring(0, 3).toUpperCase() : "DOC";
    int n = ThreadLocalRandom.current().nextInt(100000, 999999);
    return prefix + "-" + java.time.Year.now() + "-" + n;
  }

  /**
   * Build the {@code status} claim's value (spec FS-0.4 D3): the IETF Token Status List shape
   * ({@code status.status_list.{idx,uri}}). {@code uri} is a provisional placeholder — the raw
   * status list id, not yet a resolvable URL — until KH-1.3 publishes the real signed bitstring
   * artifact endpoint. This method fixes the claim's *shape* now, per spec FS-0.4 §7.
   */
  private static Map<String, Object> statusClaim(StatusAllocation allocation) {
    Map<String, Object> statusList = new LinkedHashMap<>();
    statusList.put("idx", allocation.idx());
    statusList.put("uri", allocation.statusListId().toString());
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("status_list", statusList);
    return status;
  }

  /**
   * Build a minimal claims-field definition from the claim keys supplied at issue time.
   *
   * <p>Real schema authoring with a typed claims editor is KH-1.x; this exists only so that {@link
   * SchemaCatalog#ensurePublished} has a non-null {@code claims_def} to persist for schemas created
   * on the fly (e.g. by the demo seeder). {@code required} reflects spec FS-0.4 D2's redefined
   * {@code sd_fields} semantics: a field is {@code required} (mandatory to disclose) exactly when
   * it is <em>not</em> in {@code sdFields}.
   */
  private static SchemaDefinition buildSchemaDefinition(
      String schemaCode, Map<String, Object> claims, List<String> sdFields, int maxUses) {
    ObjectNode claimsDef = JSON.createObjectNode();
    for (String field : claims.keySet()) {
      ObjectNode fieldDef = claimsDef.putObject(field);
      fieldDef.put("type", "string");
      fieldDef.put("required", !sdFields.contains(field));
      ObjectNode label = fieldDef.putObject("label_i18n");
      label.put("en", field);
      label.put("ar", field);
    }
    return new SchemaDefinition(
        schemaCode,
        1,
        new LocalizedText(schemaCode, schemaCode),
        claimsDef.toString(),
        sdFields,
        maxUses);
  }

  /**
   * Check a JWT's signature by resolving its {@code kid} header strictly through {@link
   * KeyVerifier} — an unknown or {@code RETIRED} {@code kid} means {@link
   * KeyVerifier#resolvePublicKey} returns empty, and there is no fallback to any other key (spec
   * FS-0.5 §4). {@link VerifyReason#UNKNOWN_KID} covers a missing/unresolvable {@code kid}; {@link
   * VerifyReason#BAD_SIGNATURE} covers a resolved key whose signature bytes don't verify (spec
   * FS-0.6a D2 splits these two — they were one generic outcome before this session).
   *
   * @return {@link VerifyReason#VALID} if the signature checks out; otherwise the specific
   *     rejection reason
   */
  private VerifyReason checkSignature(SignedJWT jwt) {
    String kid = jwt.getHeader().getKeyID();
    if (kid == null) {
      return VerifyReason.UNKNOWN_KID;
    }
    Optional<PublicKeyHandle> handle = keyVerifier.resolvePublicKey(kid);
    if (handle.isEmpty()) {
      return VerifyReason.UNKNOWN_KID;
    }
    try {
      return jwt.verify(new ECDSAVerifier(handle.get().publicKey()))
          ? VerifyReason.VALID
          : VerifyReason.BAD_SIGNATURE;
    } catch (JOSEException e) {
      return VerifyReason.BAD_SIGNATURE;
    }
  }

  /** Strip the SD-JWT digest machinery ({@code _sd}, {@code _sd_alg}) from a raw claims map. */
  private static Map<String, Object> structuralClaims(Map<String, Object> rawClaims) {
    Map<String, Object> copy = new LinkedHashMap<>(rawClaims);
    copy.remove("_sd");
    copy.remove("_sd_alg");
    return copy;
  }

  /**
   * Resolve the set of {@code claims_def} field names a presentation of this schema must always
   * disclose (spec FS-0.4 D2): every field name in {@code claims_def} that is not listed in {@code
   * sd_fields}.
   */
  private static Set<String> mandatoryFields(SchemaRef schema) {
    JsonNode claimsDef;
    try {
      claimsDef = JSON.readTree(schema.claimsDefJson());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Stored claims_def is not valid JSON for schema " + schema.id(), e);
    }
    Set<String> mandatory = new LinkedHashSet<>();
    claimsDef.fieldNames().forEachRemaining(mandatory::add);
    mandatory.removeAll(schema.sdFields());
    return mandatory;
  }

  /**
   * Extract the disclosures from an SD-JWT presentation and join them with {@code ~}, the exact
   * plaintext {@link ClaimsEncryptionService#encrypt} encrypts for {@code disclosures_enc} (spec
   * FS-0.4 D7).
   */
  private static String joinDisclosures(String sdJwtPresentation) {
    SDJWT parsed = SDJWT.parse(sdJwtPresentation);
    return parsed.getDisclosures().stream()
        .map(Disclosure::getDisclosure)
        .collect(Collectors.joining("~"));
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
