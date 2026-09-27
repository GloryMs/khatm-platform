package sy.khatm.platform.credential.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.IssuanceIdempotency.Scope;
import sy.khatm.platform.credential.domain.IssuanceIdempotency.State;
import sy.khatm.platform.credential.persistence.CredentialRepository;
import sy.khatm.platform.credential.persistence.IssuanceIdempotencyRepository;
import sy.khatm.platform.holder.api.HolderDirectory;
import sy.khatm.platform.holder.api.HolderRef;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.AuthenticationException;
import sy.khatm.platform.shared.error.AuthorizationException;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.IntegrityException;
import sy.khatm.platform.shared.error.KhatmException;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;

/**
 * Persistent idempotency for {@code POST /credentials/issue} and {@code /bulk} (KH-2.8.2, spec
 * FS-2.7a D5–D7): a connector can retry any issuance safely, and a retry never issues twice.
 *
 * <p><b>Who must send a key (D5, veto V6):</b> a machine issuer client always ({@code 400
 * KH-IDEM-0400} otherwise); every other caller may, and a sent key is honored. A key is 1–128
 * printable ASCII characters.
 *
 * <p><b>Claim first (D6):</b> {@link #claim} inserts an {@code IN_PROGRESS} row before any work
 * ({@code INSERT ... ON CONFLICT DO NOTHING} — the unique index decides a race in the database).
 * For {@code /issue} this happens inside the issuance transaction, so a failed issuance rolls the
 * row back and a concurrent twin waits on the index until the winner commits, then replays it.
 * {@code /bulk} is deliberately not one transaction (each row commits on its own), so its row
 * commits {@code IN_PROGRESS} first and becomes {@code DONE} with a result snapshot at the end; a
 * twin arriving meanwhile gets {@code 409 KH-IDEM-0409}.
 *
 * <p><b>Replay (D7, vetoes V2–V4):</b> same key + same canonical body → the original result, with
 * the claim-code rules of {@link ClaimCodeReissuer}. Same key + different body → {@code 422
 * KH-IDEM-0422}. The body hash is SHA-256 over canonical JSON (object keys sorted, {@code null}s
 * dropped), so key order and explicit nulls never matter; the key itself is not hashed into it.
 *
 * <p><b>Nothing sensitive leaves this class:</b> no key, code, or {@code holderRef} is logged;
 * audit rows carry only the key's SHA-256 (D11).
 *
 * <p>Module-private.
 */
@Service
public class IssuanceIdempotencyGuard {

  private static final Logger log = LoggerFactory.getLogger(IssuanceIdempotencyGuard.class);

  /** Printable ASCII (space through tilde), 1–128 characters (spec FS-2.7a D5). */
  private static final Pattern KEY_SHAPE = Pattern.compile("^[\\x20-\\x7E]{1,128}$");

  private static final ObjectMapper JSON = new ObjectMapper();

  private final IssuanceIdempotencyRepository rows;
  private final CredentialRepository credentials;
  private final HolderDirectory holders;
  private final ClaimCodeReissuer reissuer;
  private final AuditService audit;
  private final Clock clock;
  private final Duration retention;

  public IssuanceIdempotencyGuard(
      IssuanceIdempotencyRepository rows,
      CredentialRepository credentials,
      HolderDirectory holders,
      ClaimCodeReissuer reissuer,
      AuditService audit,
      Clock clock,
      @Value("${khatm.issuance.idempotency.retention:P30D}") Duration retention) {
    this.rows = rows;
    this.credentials = credentials;
    this.holders = holders;
    this.reissuer = reissuer;
    this.audit = audit;
    this.clock = clock;
    this.retention = retention;
  }

  /** The outcome of {@link #claim}: this caller owns the key, or an earlier result exists. */
  public record Claim(UUID rowId, IssuanceIdempotency existing) {

    /** {@code true} when the key was already completed and the caller must replay. */
    public boolean isReplay() {
      return existing != null;
    }
  }

  /**
   * The key this request is bound to, or {@code null} when idempotency does not apply.
   *
   * @param context the request's headers
   * @param issuerClient the caller is a machine issuer client (the header is mandatory for it)
   * @return the validated key, or {@code null} for a keyless human/tenant-key request
   * @throws ValidationException {@link ErrorCode#KH_IDEM_0400} for a missing mandatory key or a
   *     malformed one
   */
  public String effectiveKey(IssuanceContext context, boolean issuerClient) {
    String key = context == null ? null : context.idempotencyKey();
    if (key == null) {
      if (issuerClient) {
        throw new ValidationException(ErrorCode.KH_IDEM_0400, "idempotency.key-invalid");
      }
      return null;
    }
    if (!KEY_SHAPE.matcher(key).matches() || key.isBlank()) {
      throw new ValidationException(ErrorCode.KH_IDEM_0400, "idempotency.key-invalid");
    }
    return key;
  }

  /**
   * SHA-256 of {@code body}'s canonical JSON: object keys sorted recursively, {@code null} values
   * dropped, array order kept.
   *
   * @param body the deserialized request record
   * @return 32 bytes
   */
  public byte[] requestHash(Object body) {
    JsonNode canonical = canonicalize(JSON.valueToTree(body));
    try {
      return sha256(JSON.writeValueAsBytes(canonical));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "A request record that was just deserialized must serialize", e);
    }
  }

  /**
   * Claim {@code key} for this request, or find the earlier result it already produced.
   *
   * <p>Joins the caller's transaction ({@code /issue}); runs and commits on its own when there is
   * none ({@code /bulk}).
   *
   * @return a claim this caller now owns ({@link Claim#rowId()}), or the completed earlier row
   * @throws ConflictException {@link ErrorCode#KH_IDEM_0409} if the earlier request is still in
   *     progress; {@link ErrorCode#KH_IDEM_0422} if it was a different body
   */
  @Transactional
  public Claim claim(Scope scope, String key, byte[] requestHash, UUID issuerClientId) {
    UUID id = Uuidv7.generate();
    Instant now = clock.instant();
    int inserted =
        rows.insertIfAbsent(
            id,
            TenantContext.current(),
            issuerClientId == null ? null : issuerClientId.toString(),
            key,
            scope.name(),
            requestHash,
            now,
            now.plus(retention));
    if (inserted == 1) {
      return new Claim(id, null);
    }
    IssuanceIdempotency existing =
        find(scope, key, issuerClientId)
            // The winner's row vanished between our conflict and this read (only a retention
            // sweep of an expired row can do that): ask the caller to retry, which then claims it.
            .orElseThrow(
                () -> new ConflictException(ErrorCode.KH_IDEM_0409, "idempotency.in-progress"));
    if (existing.getState() == State.IN_PROGRESS) {
      throw new ConflictException(ErrorCode.KH_IDEM_0409, "idempotency.in-progress");
    }
    if (!Arrays.equals(existing.getRequestHash(), requestHash)) {
      throw new ConflictException(ErrorCode.KH_IDEM_0422, "idempotency.key-reused");
    }
    return new Claim(null, existing);
  }

  /** Marks an {@code ISSUE} claim done; runs inside the issuance transaction. */
  @Transactional
  public void completeIssue(UUID rowId, UUID credentialId) {
    rows.findById(rowId).orElseThrow(() -> missingRow(rowId)).completeIssue(credentialId);
  }

  /**
   * Marks a {@code BULK} claim done with its code-free result snapshot (veto V4): per row only
   * {@code index}, {@code status}, {@code id}, {@code ref}, and for a failure its error code,
   * message key and arguments — never a claim code, a {@code holderRef}, or a claim value.
   */
  @Transactional
  public void completeBulk(UUID rowId, List<BulkIssueItemOutcome> results) {
    ArrayNode snapshot = JsonNodeFactory.instance.arrayNode();
    for (BulkIssueItemOutcome result : results) {
      ObjectNode row = snapshot.addObject();
      row.put("index", result.index());
      if (result.succeeded()) {
        row.put("status", "ISSUED");
        row.put("id", result.id());
        row.put("ref", result.ref());
      } else {
        row.put("status", "FAILED");
        ObjectNode error = row.putObject("error");
        error.put("code", result.error().errorCode().name());
        error.put("messageKey", result.error().messageKey());
        ArrayNode args = error.putArray("args");
        for (Object arg : result.error().args()) {
          args.add(String.valueOf(arg));
        }
      }
    }
    rows.findById(rowId).orElseThrow(() -> missingRow(rowId)).completeBulk(snapshot.toString());
  }

  /**
   * The replayed {@code /issue} response for a completed row (D7 corrected by vetoes V2/V3).
   *
   * @param row the completed {@code ISSUE} row
   * @param claimCodeMode the request asked for {@code mintClaimCode}
   * @param key the raw key (hashed for the audit row only)
   */
  public IssueResponse replayIssue(IssuanceIdempotency row, boolean claimCodeMode, String key) {
    Credential credential =
        credentials.findById(row.getCredentialId()).orElseThrow(() -> missingRow(row.getId()));
    ReplayDelivery delivery = reissuer.replay(credential.getId(), claimCodeMode);
    auditReplay(
        Scope.ISSUE,
        credential.getRef(),
        key,
        delivery.claimCodeReissued(),
        delivery.deliveryLost());
    return new IssueResponse(
        credential.getId().toString(),
        credential.getRef(),
        null,
        delivery.claimed(),
        credential.getIssuerClientId() == null ? null : credential.getIssuerClientId().toString(),
        holderRefOf(credential),
        delivery.claimCode(),
        delivery.claimCodeExpiresAt(),
        delivery.claimCodeReissued(),
        delivery.deliveryLost());
  }

  /**
   * The replayed {@code /bulk} outcome (veto V4): the stored per-row results, with a fresh claim
   * code reissued for every issued row still unclaimed when {@code mintClaimCodes} was set. Each
   * row's reissue commits on its own, like the original batch's rows.
   */
  public BulkIssueOutcome replayBulk(
      IssuanceIdempotency row, boolean claimCodeMode, String key, String schemaCode) {
    JsonNode snapshot;
    try {
      snapshot = JSON.readTree(row.getResultSnapshot());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "issuance_idempotency " + row.getId() + " has a bad snapshot", e);
    }
    List<BulkIssueItemOutcome> results = new ArrayList<>(snapshot.size());
    int succeeded = 0;
    int reissued = 0;
    int lost = 0;
    for (JsonNode item : snapshot) {
      int index = item.get("index").asInt();
      if ("ISSUED".equals(item.get("status").asText())) {
        String id = item.get("id").asText();
        Credential credential =
            credentials.findById(UUID.fromString(id)).orElseThrow(() -> missingRow(row.getId()));
        // Bulk never returns an sdJwt, so direct mode has nothing to lose — only `claimed` matters.
        ReplayDelivery delivery = reissuer.replay(credential.getId(), claimCodeMode);
        reissued += delivery.claimCodeReissued() ? 1 : 0;
        lost += claimCodeMode && delivery.deliveryLost() ? 1 : 0;
        results.add(
            new BulkIssueItemOutcome(
                index,
                id,
                item.get("ref").asText(),
                delivery.claimCode(),
                holderRefOf(credential),
                delivery.claimed(),
                null));
        succeeded++;
      } else {
        JsonNode error = item.get("error");
        List<Object> args = new ArrayList<>();
        error.get("args").forEach(arg -> args.add(arg.asText()));
        results.add(
            new BulkIssueItemOutcome(
                index,
                null,
                null,
                null,
                null,
                false,
                rebuild(
                    ErrorCode.valueOf(error.get("code").asText()),
                    error.get("messageKey").asText(),
                    args.toArray())));
      }
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("keySha256", HexFormat.of().formatHex(sha256(key.getBytes(StandardCharsets.UTF_8))));
    detail.put("scope", Scope.BULK.name());
    detail.put("claimCodeReissued", reissued > 0);
    detail.put("deliveryLost", lost > 0);
    detail.put("reissuedCount", reissued);
    audit.record(AuditAction.ISSUANCE_REPLAYED, "credential", schemaCode, detail);
    log.info("bulk issuance replayed scope=BULK rows={} reissued={}", results.size(), reissued);
    return new BulkIssueOutcome(
        results.size(), succeeded, results.size() - succeeded, results, true);
  }

  private Optional<IssuanceIdempotency> find(Scope scope, String key, UUID issuerClientId) {
    return issuerClientId == null
        ? rows.findByIssuerClientIdIsNullAndScopeAndIdempotencyKey(scope.name(), key)
        : rows.findByIssuerClientIdAndScopeAndIdempotencyKey(issuerClientId, scope.name(), key);
  }

  private void auditReplay(
      Scope scope, String entityRef, String key, boolean reissued, boolean deliveryLost) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("keySha256", HexFormat.of().formatHex(sha256(key.getBytes(StandardCharsets.UTF_8))));
    detail.put("scope", scope.name());
    detail.put("claimCodeReissued", reissued);
    detail.put("deliveryLost", deliveryLost);
    audit.record(AuditAction.ISSUANCE_REPLAYED, "credential", entityRef, detail);
    log.info(
        "issuance replayed ref={} scope={} claimCodeReissued={} deliveryLost={}",
        entityRef,
        scope,
        reissued,
        deliveryLost);
  }

  private String holderRefOf(Credential credential) {
    return holders.findById(credential.getHolderId()).map(HolderRef::pseudoRef).orElse(null);
  }

  /**
   * A stored row error as the {@link KhatmException} subtype its HTTP status implies — the bulk
   * outcome carries exceptions, and the web layer only reads code, message key and arguments.
   */
  private static KhatmException rebuild(ErrorCode code, String messageKey, Object[] args) {
    int status = code.httpStatus().value();
    return switch (status) {
      case 401 -> new AuthenticationException(code, messageKey, args);
      case 403 -> new AuthorizationException(code, messageKey, args);
      case 404 -> new NotFoundException(code, messageKey, args);
      case 409 -> new ConflictException(code, messageKey, args);
      default ->
          status >= 500
              ? new IntegrityException(code, messageKey, args)
              : new ValidationException(code, messageKey, args);
    };
  }

  /** Recursively sorts object keys and drops {@code null}s; array order is significant. */
  private static JsonNode canonicalize(JsonNode node) {
    if (node.isObject()) {
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if (!field.getValue().isNull()) {
          sorted.put(field.getKey(), canonicalize(field.getValue()));
        }
      }
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      sorted.forEach(out::set);
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      node.forEach(element -> out.add(canonicalize(element)));
      return out;
    }
    return node;
  }

  private static IllegalStateException missingRow(UUID rowId) {
    return new IllegalStateException(
        "issuance_idempotency " + rowId + " references a row that must exist");
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm", e);
    }
  }
}
