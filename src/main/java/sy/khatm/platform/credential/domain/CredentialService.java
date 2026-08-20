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
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.consumer.api.ConsumingPartyRef;
import sy.khatm.platform.consumer.api.ConsumingPartyRegistry;
import sy.khatm.platform.credential.api.AttestationRequest;
import sy.khatm.platform.credential.api.ConsumeRequest;
import sy.khatm.platform.credential.api.ConsumeResponse;
import sy.khatm.platform.credential.api.CredentialPage;
import sy.khatm.platform.credential.api.CredentialSummary;
import sy.khatm.platform.credential.api.CredentialView;
import sy.khatm.platform.credential.api.HolderStatusResponse;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.api.IssuerLineageEntry;
import sy.khatm.platform.credential.api.VerifyResponse;
import sy.khatm.platform.credential.events.CredentialIssued;
import sy.khatm.platform.credential.persistence.ClaimCodeRepository;
import sy.khatm.platform.credential.persistence.ConsumptionEventRepository;
import sy.khatm.platform.credential.persistence.CredentialRepository;
import sy.khatm.platform.holder.api.HolderDirectory;
import sy.khatm.platform.holder.api.HolderRef;
import sy.khatm.platform.key.api.KeySigner;
import sy.khatm.platform.key.api.KeyVerifier;
import sy.khatm.platform.key.api.PublicKeyHandle;
import sy.khatm.platform.key.api.SignResult;
import sy.khatm.platform.rbac.api.CurrentActor;
import sy.khatm.platform.rbac.api.CurrentActorResolver;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaDefinition;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.AuthorizationException;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.IntegrityException;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.shared.error.VerifyReason;
import sy.khatm.platform.status.api.StatusAllocation;
import sy.khatm.platform.status.api.StatusListAllocator;
import sy.khatm.platform.status.api.StatusListLookup;
import sy.khatm.platform.status.api.StatusListRef;
import sy.khatm.platform.status.api.StatusListRevoker;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

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
  private static final int DEFAULT_CLAIM_CODE_TTL_MINUTES = 15;
  private static final String SD_ALG = "sha-256";
  private static final int DEFAULT_SEARCH_PAGE_SIZE = 20;
  private static final int MAX_SEARCH_PAGE_SIZE = 100;
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
  private final StatusListRevoker statusRevoker;
  private final StatusListLookup statusLookup;
  private final ConsumingPartyRegistry consumingParties;
  private final StringRedisTemplate redis;
  private final CredentialMapper mapper;
  private final ClaimsEncryptionService claimsEncryption;
  private final ApplicationEventPublisher eventPublisher;
  private final AuditService audit;
  private final CurrentActorResolver currentActorResolver;
  private final AtomicConsumptionRecorder consumptionRecorder;
  private final TenantDirectory tenants;

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
      StatusListRevoker statusRevoker,
      StatusListLookup statusLookup,
      ConsumingPartyRegistry consumingParties,
      StringRedisTemplate redis,
      CredentialMapper mapper,
      ClaimsEncryptionService claimsEncryption,
      ApplicationEventPublisher eventPublisher,
      AuditService audit,
      CurrentActorResolver currentActorResolver,
      AtomicConsumptionRecorder consumptionRecorder,
      TenantDirectory tenants) {
    this.credentials = credentials;
    this.events = events;
    this.claimCodes = claimCodes;
    this.keys = keys;
    this.keyVerifier = keyVerifier;
    this.schemas = schemas;
    this.holders = holders;
    this.statusLists = statusLists;
    this.statusRevoker = statusRevoker;
    this.statusLookup = statusLookup;
    this.consumingParties = consumingParties;
    this.redis = redis;
    this.mapper = mapper;
    this.claimsEncryption = claimsEncryption;
    this.eventPublisher = eventPublisher;
    this.audit = audit;
    this.currentActorResolver = currentActorResolver;
    this.consumptionRecorder = consumptionRecorder;
    this.tenants = tenants;
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
        req.schemaId() != null
            ? schemas.requirePublishedById(req.schemaId())
            : schemas.ensurePublished(buildSchemaDefinition(schemaCode, claims, sdFields, maxUses));
    validateAttestation(schemaRef, req.attestation());
    validateClaimPatterns(schemaRef, claims);
    HolderRef holderRef = holders.ensureHolder(holderPseudoRef);
    StatusAllocation allocation = statusLists.allocate(DEFAULT_STATUS_LIST_CODE);
    // KH-1.3 D7: the allocate() call above always creates-or-finds the list row first, so a
    // lookup right after it can never come back empty — the real, resolvable public URL an
    // offline verifier needs baked into the SD-JWT itself, replacing the pre-KH-1.3 placeholder
    // (the raw status_list_id) that spec FS-0.4 D3 originally shipped as a shape-only stand-in.
    String statusListUri =
        statusLookup
            .findRef(allocation.statusListId())
            .map(StatusListRef::uri)
            .orElseGet(() -> allocation.statusListId().toString());

    Instant now = Instant.now();
    Instant exp = now.plus(Duration.ofMinutes(validMinutes));
    String ref = buildRef(schemaRef.code());
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
    sdBuilder.putClaim("status", statusClaim(allocation, statusListUri));
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

    // KH-2.4 (spec FS-2.4 item 2): recorded before CREDENTIAL_ISSUED, same transaction — a
    // signing/persistence failure earlier in this method never reaches here, and any failure
    // between here and commit rolls this row back together with everything else (AuditService
    // joins the caller's own physical transaction), so there is never an orphan SCAN_ATTESTED row.
    // validateAttestation above guarantees req.attestation() != null here exactly when the schema
    // requires it.
    if (req.attestation() != null) {
      String note = req.attestation().note();
      Map<String, Object> detail = note == null || note.isBlank() ? null : Map.of("note", note);
      audit.record(AuditAction.SCAN_ATTESTED, "credential", ref, detail);
    }

    // ADR-09: publish CredentialIssued inside this transaction. Spring Modulith writes it to the
    // event_publication outbox now (same tx) and externalizes it to the khatm.credential.events
    // Redis Stream after commit. Proof-shaped payload only — ref + timestamps, never claims or
    // disclosures (SEC §9 applies to the stream exactly as to logs).
    eventPublisher.publishEvent(new CredentialIssued(ref, null, now, c.getTenantId()));
    audit.record(AuditAction.CREDENTIAL_ISSUED, "credential", ref, null);

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

  /**
   * Mint a fresh wallet claim code for an already-issued credential (KH-1.2.2, spec FS-1.2.1 D2's
   * "the issuer re-issues a claim code" recovery path exposed over HTTP — this is the
   * console-facing counterpart to {@link #issueClaimCode}, not a new mechanism). The platform never
   * persists a presentation's disclosures outside a {@code claim_code} row (P1), so {@code
   * sdJwtPresentation} must be one the caller already holds — the exact string {@link #issue}
   * returned, retained by the issuer for precisely this recovery case.
   *
   * <p>Enforces "one live code per credential" (spec FS-1.2.1's single-shot philosophy, extended to
   * minting): any prior pending code for this credential is voided first, via the same
   * disclosures_enc-zeroing mechanism {@link
   * sy.khatm.platform.credential.worker.ClaimCodeExpiryWorker#sweep} and {@link
   * sy.khatm.platform.credential.domain.ClaimRedemptionService#redeem} already use — a stale code
   * left over from a previous mint becomes unredeemable (generic {@code KH-CLM-0404}) rather than a
   * second live code competing with the new one.
   *
   * @param credentialId the credential to mint a claim code for
   * @param sdJwtPresentation the full SD-JWT presentation covering this credential's disclosures
   * @param ttlMinutes how long the new code remains claimable; {@code null} defaults to {@value
   *     #DEFAULT_CLAIM_CODE_TTL_MINUTES}
   * @return the raw one-time code (shown to the caller exactly once) and its expiry
   * @throws NotFoundException {@link ErrorCode#KH_CRD_0404} if no credential with this id exists
   * @throws ConflictException {@link ErrorCode#KH_CRD_0409} if the credential is revoked or past
   *     its validity window
   */
  @Transactional
  public ClaimCodeIssued mintClaimCode(
      UUID credentialId, String sdJwtPresentation, Integer ttlMinutes) {
    Credential credential =
        credentials
            .findById(credentialId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCode.KH_CRD_0404, "credential.not-found", credentialId));
    if (credential.isRevoked() || credential.getValidTo().isBefore(Instant.now())) {
      throw new ConflictException(ErrorCode.KH_CRD_0409, "credential.not-claimable");
    }

    claimCodes.zeroPendingForCredential(credentialId);

    Duration ttl =
        Duration.ofMinutes(ttlMinutes == null ? DEFAULT_CLAIM_CODE_TTL_MINUTES : ttlMinutes);
    ClaimCodeIssued minted = issueClaimCode(credentialId, sdJwtPresentation, ttl);

    audit.record(AuditAction.CLAIM_CODE_ISSUED, "credential", credential.getRef(), null);
    return minted;
  }

  // ── Verify (online: signature + status) ──────────────────────────────────

  /**
   * Verify an SD-JWT presentation. Thin wrapper over {@link #verifyOutcome} for every pre-existing
   * caller that only ever needed the public wire result — the credential's issuing tenant (needed
   * only to correctly attribute the {@code CREDENTIAL_VERIFY_OK}/{@code _FAILED} audit write,
   * {@code credential.web.CredentialController#verify}'s job) is resolved by that method but
   * deliberately not part of this one's return shape.
   */
  @Transactional(readOnly = true)
  public VerifyResponse verify(String presentation) {
    return verifyOutcome(presentation).response();
  }

  /**
   * Verify an SD-JWT presentation, pairing the result with the credential's issuing tenant when one
   * was actually resolved — {@code null} on every early-exit branch below that never reaches a
   * credential row (malformed presentation, bad signature, unknown {@code kid}, unknown {@code
   * ref}), since there is genuinely no tenant to attribute to there. See {@link VerifyOutcome}'s
   * own Javadoc for why this split exists (KH-2.6b's own aggregated report surfaced that {@code
   * CredentialController#verify} had been attributing every verify audit row to the platform
   * default tenant unconditionally, not just on these genuine no-credential-resolved branches).
   */
  @Transactional(readOnly = true)
  public VerifyOutcome verifyOutcome(String presentation) {
    if (presentation == null) {
      return noTenant(result(false, VerifyReason.MALFORMED, null, null, false));
    }

    String compactJwt;
    List<Disclosure> disclosures;
    if (presentation.contains("~")) {
      SDJWT sdJwt;
      try {
        sdJwt = SDJWT.parse(presentation);
      } catch (RuntimeException e) {
        return noTenant(result(false, VerifyReason.MALFORMED, null, null, false));
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
      return noTenant(result(false, VerifyReason.MALFORMED, null, null, false));
    }

    VerifyReason signatureResult = checkSignature(parsed);
    if (signatureResult != VerifyReason.VALID) {
      return noTenant(result(false, signatureResult, null, null, false));
    }

    Map<String, Object> rawClaims = claimsSet.getClaims();
    Map<String, Object> structural = structuralClaims(rawClaims);

    Date exp = claimsSet.getExpirationTime();
    if (exp != null && exp.toInstant().isBefore(Instant.now())) {
      return noTenant(result(false, VerifyReason.EXPIRED, structural, null, false));
    }

    String ref = (String) rawClaims.get("ref");
    Optional<Credential> maybe = ref == null ? Optional.empty() : credentials.findByRef(ref);
    if (maybe.isEmpty()) {
      // Signature valid but we have no local record — still authentic offline. The
      // mandatory-disclosure check (D2) needs the issuing schema, which we only know via this
      // DB row, so an unknown ref skips straight to this existing early-exit (spec FS-0.4 §4
      // step 2 reuses this path unchanged).
      return noTenant(
          result(true, VerifyReason.VALID_SIGNATURE_UNKNOWN_REF, structural, null, false));
    }
    Credential c = maybe.get();
    String issuerSlug = tenants.findById(c.getTenantId()).map(TenantRef::slug).orElse("");

    // KH-1.3 D6: now that the credential row is in hand, resolve its status list and surface the
    // additive verify-time fields. statusListChecked=true here means this result is backed by the
    // platform's live status list (we have the row + resolved the list) — by D3 the bitstring bit
    // and the `revoked` column are always consistent, so `revoked` below is the bitstring-grounded
    // truth. The early-exit branches above never reached a row, so they carry
    // statusListChecked=false.
    Optional<StatusListRef> statusRef = findRefForTenant(c.getTenantId(), c.getStatusListId());
    boolean statusListChecked = statusRef.isPresent();
    Long statusListVersion = statusRef.map(StatusListRef::version).orElse(null);
    String statusListUri = statusRef.map(StatusListRef::uri).orElse(null);

    // KH-2.6a (spec FS-2.5 §5/§7): the issuing tenant's ancestor chain, nearest first — display
    // metadata only (§1), resolved once here alongside the other post-row fields above and
    // threaded through every branch below exactly like statusListChecked/statusListVersion/
    // statusListUri already are.
    List<IssuerLineageEntry> issuerLineage =
        tenants.ancestors(c.getTenantId()).stream()
            .map(ancestor -> new IssuerLineageEntry(ancestor.slug(), ancestor.nameI18n()))
            .toList();

    if (c.isRevoked()) {
      return withTenant(
          result(
              false,
              VerifyReason.REVOKED,
              structural,
              c.getUsesRemaining(),
              true,
              statusListChecked,
              statusListVersion,
              statusListUri,
              issuerLineage),
          c.getTenantId(),
          issuerSlug);
    }

    // FS-1.6 D4: an exhausted (but not explicitly revoked) credential is a distinct, non-error
    // domain result — checked right after REVOKED, before the disclosure-shape checks below, since
    // those are about the presentation's own integrity, not the credential's lifecycle state.
    if (c.getUsesRemaining() <= 0) {
      return withTenant(
          result(
              false,
              VerifyReason.EXHAUSTED,
              structural,
              c.getUsesRemaining(),
              false,
              statusListChecked,
              statusListVersion,
              statusListUri,
              issuerLineage),
          c.getTenantId(),
          issuerSlug);
    }

    // D8: _sd_alg must be sha-256.
    if (!SD_ALG.equals(rawClaims.get("_sd_alg"))) {
      return withTenant(
          result(
              false,
              VerifyReason.BAD_SD_ALG,
              structural,
              c.getUsesRemaining(),
              false,
              statusListChecked,
              statusListVersion,
              statusListUri,
              issuerLineage),
          c.getTenantId(),
          issuerSlug);
    }
    List<?> sdDigests = rawClaims.get("_sd") instanceof List<?> list ? list : List.of();

    // D8: every presented disclosure's digest must be in _sd, and no duplicate claim names.
    Map<String, Object> disclosed = new LinkedHashMap<>();
    for (Disclosure d : disclosures) {
      String claimName = d.getClaimName();
      if (claimName == null || !sdDigests.contains(d.digest())) {
        return withTenant(
            result(
                false,
                VerifyReason.FORGED_DISCLOSURE,
                structural,
                c.getUsesRemaining(),
                false,
                statusListChecked,
                statusListVersion,
                statusListUri,
                issuerLineage),
            c.getTenantId(),
            issuerSlug);
      }
      if (disclosed.containsKey(claimName)) {
        return withTenant(
            result(
                false,
                VerifyReason.DUPLICATE_DISCLOSURE,
                structural,
                c.getUsesRemaining(),
                false,
                statusListChecked,
                statusListVersion,
                statusListUri,
                issuerLineage),
            c.getTenantId(),
            issuerSlug);
      }
      disclosed.put(claimName, d.getClaimValue());
    }

    // D2: every claims_def field NOT in schema.sd_fields is mandatory to disclose.
    Optional<SchemaRef> schemaRef = schemas.findById(c.getSchemaId());
    if (schemaRef.isPresent()) {
      for (String mandatoryField : mandatoryFields(schemaRef.get())) {
        if (!disclosed.containsKey(mandatoryField)) {
          return withTenant(
              result(
                  false,
                  VerifyReason.WITHHELD_MANDATORY_CLAIM,
                  structural,
                  c.getUsesRemaining(),
                  false,
                  statusListChecked,
                  statusListVersion,
                  statusListUri,
                  issuerLineage),
              c.getTenantId(),
              issuerSlug);
        }
      }
    }

    Map<String, Object> verifiedClaims = new LinkedHashMap<>(structural);
    verifiedClaims.putAll(disclosed);
    return withTenant(
        result(
            true,
            VerifyReason.VALID,
            verifiedClaims,
            c.getUsesRemaining(),
            false,
            statusListChecked,
            statusListVersion,
            statusListUri,
            issuerLineage),
        c.getTenantId(),
        issuerSlug);
  }

  private static VerifyOutcome noTenant(VerifyResponse response) {
    return new VerifyOutcome(response, null, null);
  }

  private static VerifyOutcome withTenant(
      VerifyResponse response, UUID tenantId, String tenantSlug) {
    return new VerifyOutcome(response, tenantId, tenantSlug);
  }

  /**
   * Build a {@link VerifyResponse} carrying {@code reason}'s wire code. {@code reasonMessage}, the
   * three status-list fields, and {@code issuerLineage} are left at their defaults — localizing the
   * message needs the request locale (a web-layer concern; the controller resolves it via {@code
   * MessageSource}, spec FS-0.6a §3), and the status/lineage fields are {@code false}/{@code null}
   * for every early-exit branch that never reached a credential row (spec FS-1.3 D6, FS-2.5 §5).
   * Post-row branches pass the resolved values explicitly.
   */
  private static VerifyResponse result(
      boolean valid,
      VerifyReason reason,
      Map<String, Object> claims,
      Integer usesRemaining,
      boolean revoked) {
    return result(valid, reason, claims, usesRemaining, revoked, false, null, null, null);
  }

  /** Full-arity builder for the post-credential-row branches that carry status-list metadata. */
  private static VerifyResponse result(
      boolean valid,
      VerifyReason reason,
      Map<String, Object> claims,
      Integer usesRemaining,
      boolean revoked,
      boolean statusListChecked,
      Long statusListVersion,
      String statusListUri,
      List<IssuerLineageEntry> issuerLineage) {
    return new VerifyResponse(
        valid,
        reason.code(),
        null,
        claims,
        usesRemaining,
        revoked,
        statusListChecked,
        statusListVersion,
        statusListUri,
        issuerLineage);
  }

  // ── Holder status (proof-of-possession lookup) ────────────────────────────

  /**
   * Look up a credential's current lifecycle status by proof of possession of its bare compact JWT
   * (spec FS-1.6 D3) — a deliberate, explicit reversal of PR #33's "no live uses-remaining channel"
   * stance, now that the holder proves possession of the signed token itself rather than merely
   * naming a credential reference.
   *
   * <p>Reuses {@link #checkSignature}, the exact same signature-verification helper {@link #verify}
   * uses, and {@link CredentialRepository#findByRef}, the same ref-lookup {@link #verify} uses — no
   * second implementation of either. Every failure mode (a malformed JWT, an unresolvable or
   * retired {@code kid}, bad signature bytes, an unknown {@code ref}) collapses to the same {@link
   * ErrorCode#KH_CRD_0404} 404 (anti-enumeration: an external caller cannot distinguish "not a real
   * credential" from "a forged one," the same collapsing judgment call {@code KH_CLM_0404} already
   * made for claim codes) — deliberately reusing the existing generic "credential not found" code
   * rather than minting a new one, since the HTTP status and client-facing message are identical to
   * every other not-found lookup in this module.
   *
   * <p><b>Multi-tenant aware, like {@link #verify}:</b> the caller ({@code
   * credential.web.CredentialController#holderStatus}) wraps this whole call in {@code
   * SystemAccessExecutor#runAsSystem} — a presented JWT may belong to any tenant, unknowable before
   * it is looked up, so this runs with no ambient {@link TenantContext} of its own, exactly {@link
   * #verify}'s existing shape. Key resolution ({@link #checkSignature}) is itself tenant-agnostic
   * (kids are globally unique), so no further per-tenant wrapping is needed here.
   *
   * @param jwt the bare compact SD-JWT
   * @return the resolved status/uses/last-consumption snapshot
   * @throws NotFoundException {@link ErrorCode#KH_CRD_0404} if the token is malformed, its
   *     signature does not verify, or its {@code ref} claim does not resolve to a known credential
   */
  @Transactional(readOnly = true)
  public HolderStatusResponse holderStatus(String jwt) {
    SignedJWT parsed;
    try {
      parsed = SignedJWT.parse(jwt);
    } catch (ParseException e) {
      throw notFound();
    }
    if (checkSignature(parsed) != VerifyReason.VALID) {
      throw notFound();
    }

    String ref;
    try {
      ref = (String) parsed.getJWTClaimsSet().getClaim("ref");
    } catch (ParseException e) {
      throw notFound();
    }
    Credential c =
        (ref == null ? Optional.<Credential>empty() : credentials.findByRef(ref))
            .orElseThrow(CredentialService::notFound);

    Instant lastConsumedAt =
        events
            .findTopByCredentialIdOrderByConsumedAtDesc(c.getId())
            .map(ConsumptionEvent::getConsumedAt)
            .orElse(null);
    return new HolderStatusResponse(
        CredentialStatus.derive(c, Instant.now()).name(),
        c.getMaxUses(),
        c.getUsesRemaining(),
        lastConsumedAt);
  }

  private static NotFoundException notFound() {
    return new NotFoundException(ErrorCode.KH_CRD_0404, "credential.not-found");
  }

  // ── Consume (atomic — the double-spend guard) ─────────────────────────────

  /**
   * Consume one use of a credential (spec FS-0.2 §3.9's double-spend guard).
   *
   * <p><b>Deliberately not {@code @Transactional}</b> — the same shape {@code
   * enforceSchemaAllowlist} already established, for a related but distinct reason (KH-1.4.1/
   * 1.4.2): the eligibility decrement, event insert, and audit row happen inside {@link
   * AtomicConsumptionRecorder#tryConsume}'s own fresh transaction, on a real separate bean (so
   * {@code @Transactional} actually applies — a self-invoked method on this instance would bypass
   * Spring's proxy entirely). If two concurrent callers share an {@code idempotencyKey} and both
   * miss the Redis fast-path cache, both can legitimately pass the eligibility check when more than
   * one use remains — but only one can insert the {@code consumption_event} row (the durable {@code
   * idempotency_key UNIQUE} fallback). The loser's {@code tryConsume} transaction has already
   * rolled back completely — including its own decrement — by the time the {@link
   * DataIntegrityViolationException} reaches this method, so this method's own connection is clean
   * and free to look up the winner's row and answer without ever seeing an aborted transaction.
   */
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

    String consumerCode = req.consumer() == null ? "unknown-consumer" : req.consumer();
    ConsumingPartyRef party = consumingParties.ensure(consumerCode);
    // idempotency_key is NOT NULL + UNIQUE in the baseline schema (the durable fallback for the
    // double-submit guard); callers who don't supply one still get a unique row.
    String eventIdemKey = callerWantsIdempotency ? callerIdemKey : Uuidv7.generate().toString();

    boolean consumed;
    try {
      consumed = consumptionRecorder.tryConsume(id, party.id(), eventIdemKey);
    } catch (DataIntegrityViolationException e) {
      // KH-1.4.1/1.4.2: a concurrent caller already recorded a consumption_event under this exact
      // idempotencyKey — a genuine double-submit (the same logical request, retried), not a
      // double-spend; the atomic decrement itself was never at risk. Confirm the winning row
      // actually exists (defensive — expected to always succeed given the constraint violation
      // that brought us here) and answer indistinguishably from the Redis fast-path hit above,
      // instead of surfacing a raw KH-SYS-0500.
      events.findByIdempotencyKey(eventIdemKey).orElseThrow(() -> e);
      return new ConsumeResponse(true, "idempotent_replay", null);
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

  /**
   * KH-1.4.3 pre-check (spec SEC §7): a {@code CONSUMING_PARTY}-authenticated caller may only
   * consume a credential whose schema is in its own {@code consuming_party_schema} allowlist —
   * deny-by-default, so an unconfigured or wrongly-scoped party can consume nothing. {@code
   * SecurityConfig}'s {@code ScopeGuard.requireScopeAndConsumingPartyKey("consume")} rule already
   * guarantees every real HTTP {@code /consume} caller authenticates as exactly this actor kind
   * before this method ever runs, so this check no-ops (by design, not by accident) for a direct
   * in-JVM service call with no security context at all — e.g. {@code ConcurrentConsumeTest}'s
   * atomicity probe, which has nothing to do with this authorization concern.
   *
   * <p><b>Deliberately not {@code @Transactional}, and called by {@code
   * credential.web.CredentialController} before {@link #consume}, not from inside it</b> — the
   * exact shape {@code ClaimRedeemThrottleService#enforce} already uses ahead of {@code
   * ClaimRedemptionService#redeem}, and for the identical reason: the {@link AuditService#record}
   * call on the denial path must commit independently, not roll back alongside the {@link
   * AuthorizationException} that's about to unwind the stack. Nesting this inside {@link
   * #consume}'s own {@code @Transactional} boundary was tried first and silently discarded every
   * {@code CONSUME_SCHEMA_DENIED} audit row along with the (correctly rolled-back) denial — caught
   * by {@code ConsumeApiKeyGateTest}'s own audit-row assertion, not by inspection.
   *
   * <p>One lean indexed read ({@link CredentialRepository#findSchemaId}, {@code schema_id} only) on
   * the common/allowed path; a malformed credential id is left for {@link #consume} itself to
   * report as it always has (this check never even reaches {@code findSchemaId} for one — the
   * {@code UUID.fromString} parse itself fails first). An unresolvable but well-formed id — most
   * commonly a genuinely unknown credential, but also the shape a bug in this check's own RLS
   * plumbing would take (KH-2.1 Part B: a schema lookup missing its tenant context closed-fails to
   * zero rows, not an error) — is deny-by-default (spec D2's isolation principle) rather than
   * silently passed through: a consuming-party key that cannot be proven to be allowed for a
   * credential's schema must never be allowed to consume it. A second read (the full entity, for
   * its {@code ref}) only happens on a denial path, to attribute the audit row correctly.
   *
   * @param rawCredentialId the request's raw {@code id} field, exactly as {@link #consume} receives
   *     it — a malformed value is silently ignored here since {@link #consume} reports {@code
   *     bad_id} as its own domain result
   * @throws AuthorizationException {@link ErrorCode#KH_CNS_0403} if the resolved actor is a
   *     consuming party whose allowlist does not cover this credential's schema, or whose schema
   *     could not be resolved at all
   */
  public void enforceSchemaAllowlist(String rawCredentialId) {
    UUID credentialId;
    try {
      credentialId = UUID.fromString(rawCredentialId);
    } catch (IllegalArgumentException e) {
      return;
    }

    Optional<CurrentActor> actor = currentActorResolver.resolve();
    if (actor.isEmpty() || actor.get().kind() != CurrentActor.ActorKind.API_KEY_CONSUMING_PARTY) {
      return;
    }
    UUID partyId = actor.get().ownerId();
    Optional<UUID> schemaId = credentials.findSchemaId(credentialId);
    if (schemaId.isPresent()
        && partyId != null
        && consumingParties.isSchemaAllowed(partyId, schemaId.get())) {
      return;
    }
    String ref =
        credentials.findById(credentialId).map(Credential::getRef).orElse(credentialId.toString());
    String messageKey =
        schemaId.isEmpty() ? "consumer.schema-unresolvable" : "consumer.schema-not-allowed";
    audit.record(
        AuditAction.CONSUME_SCHEMA_DENIED,
        "credential",
        ref,
        Map.of(
            "schemaId", schemaId.map(UUID::toString).orElse("unresolved"),
            "party", String.valueOf(partyId)));
    throw new AuthorizationException(ErrorCode.KH_CNS_0403, messageKey);
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
    // KH-1.3 D3: flip the status-list bit inside this same transaction, so the bitstring truth and
    // the fast-path `revoked` column commit or roll back together — no window where revoked=true
    // but
    // the bit is still 0. The bit flip raises the list's version and publishes a StatusListChanged
    // event (externalized after commit) for the worker to re-sign and republish the artifact.
    statusRevoker.revoke(c.getStatusListId(), c.getStatusIdx());
    audit.record(AuditAction.CREDENTIAL_REVOKED, "credential", id.toString(), null);
    return true;
  }

  public Optional<CredentialView> getView(UUID id) {
    return credentials.findById(id).map(mapper::toView);
  }

  // ── Search (KH-1.1.4) ────────────────────────────────────────────────────

  /**
   * Search/list credentials for the current tenant (KH-1.1.4, {@code GET /api/v1/credentials}) —
   * every filter is optional and AND-combined, sorted by {@code issuedAt} descending.
   *
   * <p><b>{@code status} filter (chore/credential-search-status-filter):</b> zero or more of {@link
   * CredentialStatus}'s names; multiple values OR together (a row matches if its derived status is
   * any one of them). {@code null}/empty means no filtering — implemented by passing every status
   * name through to {@link CredentialRepository#search}, never a separate code path, so "no filter"
   * and "every status selected" are the same request under the hood. A single {@link Instant} is
   * captured once and reused for both the repository's filter decision and each returned row's own
   * displayed {@code status} — see {@link CredentialStatus}'s Javadoc for why this single-instant
   * discipline is what guarantees a row can never show a status it was just filtered out of (or
   * vice versa).
   *
   * @param ref exact credential ref match, or {@code null} for no filter
   * @param pseudoRef exact holder pseudoRef match, or {@code null} for no filter; an unknown
   *     pseudoRef short-circuits to an empty page rather than querying with a sentinel holder id
   * @param schemaId exact schema id match, or {@code null} for no filter
   * @param revoked exact revoked-flag match, or {@code null} for no filter
   * @param statuses zero or more {@link CredentialStatus} names to OR-filter on, or {@code null}/
   *     empty for no filter
   * @param page zero-based page number; negative values are clamped to {@code 0}
   * @param size requested page size; clamped to {@code [1, 100]}
   * @return the matching page
   * @throws ValidationException {@link ErrorCode#KH_SYS_0400} if {@code statuses} contains a value
   *     that is not a {@link CredentialStatus} name
   */
  @Transactional(readOnly = true)
  public CredentialPage search(
      String ref,
      String pseudoRef,
      UUID schemaId,
      Boolean revoked,
      List<String> statuses,
      Integer page,
      Integer size) {
    int safePage = page == null ? 0 : Math.max(0, page);
    int safeSize =
        Math.max(1, Math.min(size == null ? DEFAULT_SEARCH_PAGE_SIZE : size, MAX_SEARCH_PAGE_SIZE));
    List<String> effectiveStatuses = resolveStatusFilter(statuses);

    UUID holderId = null;
    if (pseudoRef != null && !pseudoRef.isBlank()) {
      Optional<HolderRef> holder = holders.findByPseudoRef(pseudoRef);
      if (holder.isEmpty()) {
        return new CredentialPage(List.of(), safePage, safeSize, 0, 0);
      }
      holderId = holder.get().id();
    }

    String exactRef = ref == null || ref.isBlank() ? null : ref;
    Instant now = Instant.now();
    Page<Credential> result =
        credentials.search(
            TenantContext.current(),
            exactRef,
            holderId,
            schemaId,
            revoked,
            effectiveStatuses,
            now,
            PageRequest.of(safePage, safeSize));
    List<CredentialSummary> items =
        result.getContent().stream().map(c -> toSummary(c, now)).toList();
    return new CredentialPage(
        items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
  }

  /**
   * Validate and dedupe the caller's requested status names, or — when none were requested — every
   * {@link CredentialStatus} name, so {@link CredentialRepository#search}'s {@code IN} clause is
   * always bound to a non-empty list and never needs its own null/empty-collection branch.
   *
   * @throws ValidationException {@link ErrorCode#KH_SYS_0400} on any name that is not a {@link
   *     CredentialStatus}
   */
  private static List<String> resolveStatusFilter(List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return Arrays.stream(CredentialStatus.values()).map(Enum::name).toList();
    }
    Set<String> validated = new LinkedHashSet<>();
    for (String raw : requested) {
      try {
        validated.add(CredentialStatus.valueOf(raw).name());
      } catch (IllegalArgumentException e) {
        throw new ValidationException(ErrorCode.KH_SYS_0400, "validation.failed");
      }
    }
    return List.copyOf(validated);
  }

  private CredentialSummary toSummary(Credential c, Instant now) {
    Optional<SchemaRef> schema = schemas.findById(c.getSchemaId());
    return new CredentialSummary(
        c.getId().toString(),
        c.getRef(),
        schema.map(SchemaRef::code).orElse(null),
        schema.map(SchemaRef::nameI18n).orElse(null),
        c.getCreatedAt(),
        c.getValidTo(),
        c.getMaxUses(),
        c.getUsesRemaining(),
        c.isRevoked(),
        CredentialStatus.derive(c, now).name(),
        c.getMaxUses() - c.getUsesRemaining());
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /**
   * Resolve a status list's reference (including its fully-qualified public URI) under {@code
   * tenantId}'s own {@link TenantContext}, not whatever tenant happens to be ambient for the
   * calling thread — KH-2.1 Part B: {@link #verify} runs under {@code SystemAccessExecutor} (no
   * principal, no ambient tenant of its own), and {@code status.domain.StatusListUriBuilder} builds
   * the {@code /sl/{tenantSlug}/...} URL from {@link TenantContext#currentSlug()} — without this,
   * every verify call would embed the {@code statusListUri} convenience field using whichever
   * tenant is ambient (the platform default in practice), even though the credential itself belongs
   * to a different tenant. {@link #issue} needs no equivalent wrapping: it always runs under the
   * issuing tenant's own ambient context already (an authenticated API-key request).
   */
  private Optional<StatusListRef> findRefForTenant(UUID tenantId, UUID statusListId) {
    String slug = tenants.findById(tenantId).map(TenantRef::slug).orElse("");
    TenantContext.set(tenantId, slug);
    try {
      return statusLookup.findRef(statusListId);
    } finally {
      TenantContext.clear();
    }
  }

  private String buildRef(String schemaCode) {
    String prefix = schemaCode.replaceAll("[^A-Za-z]", "");
    prefix = prefix.length() >= 3 ? prefix.substring(0, 3).toUpperCase() : "DOC";
    int n = ThreadLocalRandom.current().nextInt(100000, 999999);
    return prefix + "-" + java.time.Year.now() + "-" + n;
  }

  /**
   * Build the {@code status} claim's value (spec FS-0.4 D3): the IETF Token Status List shape
   * ({@code status.status_list.{idx,uri}}). {@code uri} is the real, resolvable {@code GET
   * /sl/{tenantSlug}/{listCode}} URL (KH-1.3 D7) baked into the SD-JWT itself at issuance — this is
   * what makes offline verification possible at all, since an offline verifier only ever has what
   * the token carries.
   */
  private static Map<String, Object> statusClaim(
      StatusAllocation allocation, String statusListUri) {
    Map<String, Object> statusList = new LinkedHashMap<>();
    statusList.put("idx", allocation.idx());
    statusList.put("uri", statusListUri);
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
        maxUses,
        // Quick-issued schemas never require attestation — that is only ever set through real
        // schema authoring (schema.web.SchemaController), never this find-or-create stand-in path.
        false);
  }

  /**
   * Deny-by-default in both directions (KH-2.4, spec FS-2.4 item 2): a schema with {@code
   * requires_attestation=true} must receive an {@code attestation} object, and one with {@code
   * requires_attestation=false} must not — no silent ignoring either way.
   *
   * @throws ValidationException {@link ErrorCode#KH_ATT_0400} if the schema requires attestation
   *     and none was submitted; {@link ErrorCode#KH_ATT_0401} if attestation was submitted but the
   *     schema does not require it
   */
  private static void validateAttestation(SchemaRef schemaRef, AttestationRequest attestation) {
    if (schemaRef.requiresAttestation() && attestation == null) {
      throw new ValidationException(ErrorCode.KH_ATT_0400, "attestation.required");
    }
    if (!schemaRef.requiresAttestation() && attestation != null) {
      throw new ValidationException(ErrorCode.KH_ATT_0401, "attestation.not-applicable");
    }
  }

  /**
   * Reject a submitted claim value that does not match its {@code claims_def} field's optional
   * {@code pattern} (KH-2.4, spec FS-2.4 item 3). A field with no {@code pattern}, or one not
   * present in {@code claims} at all, is not checked here — presence/required-ness at issuance is
   * unrelated to this format constraint.
   *
   * @throws ValidationException {@link ErrorCode#KH_SCH_0400} — the same "standard
   *     schema-validation error envelope" {@code SchemaAuthoringService} already uses, since a
   *     claim value failing its schema's own pattern is a schema-validation failure, not a new
   *     failure vocabulary
   */
  private static void validateClaimPatterns(SchemaRef schemaRef, Map<String, Object> claims) {
    JsonNode claimsDef;
    try {
      claimsDef = JSON.readTree(schemaRef.claimsDefJson());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Stored claims_def is not valid JSON for schema " + schemaRef.id(), e);
    }
    Iterator<Map.Entry<String, JsonNode>> fields = claimsDef.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      JsonNode patternNode = field.getValue().get("pattern");
      if (patternNode == null || patternNode.isNull()) {
        continue;
      }
      Object value = claims.get(field.getKey());
      if (value == null) {
        continue;
      }
      if (!Pattern.matches(patternNode.asText(), String.valueOf(value))) {
        throw new ValidationException(
            ErrorCode.KH_SCH_0400,
            "schema.validation-failed",
            "claim \"" + field.getKey() + "\" does not match the required pattern");
      }
    }
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
