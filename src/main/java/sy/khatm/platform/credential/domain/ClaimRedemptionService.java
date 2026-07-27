package sy.khatm.platform.credential.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.persistence.ClaimCodeRepository;
import sy.khatm.platform.credential.persistence.CredentialRepository;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaDetail;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.status.api.StatusListLookup;
import sy.khatm.platform.status.api.StatusListRef;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

/**
 * Hands a freshly issued credential to a wallet exactly once, then destroys the platform's only
 * copy of its disclosures (spec FS-1.2.1, closing the on-claim third of the long-open {@code
 * disclosures_enc} blocker — FS-0.2 §3.7).
 *
 * <p><b>D2 — single-shot, no grace window:</b> {@link #redeem} runs lock → validate → decrypt → set
 * {@code claimed_at} → zero {@code disclosures_enc} → audit → commit as one transaction, and
 * returns a {@link ClaimRedeemResult} built entirely from the in-memory decrypted material — {@code
 * credential.web.ClaimController} only assembles the HTTP response from that returned value, which
 * happens after Spring's transactional advice has already committed (declarative
 * {@code @Transactional} commits inside the proxy's invocation, before control returns to the
 * caller). If the response never reaches the wallet (e.g. the connection drops after commit), the
 * material is gone; recovery is the issuer creating a new claim code from the console, not a retry
 * of this call.
 *
 * <p><b>D5 — one generic failure:</b> unknown code, malformed code, expired, already claimed, or
 * expiry-zeroed by {@link sy.khatm.platform.credential.worker.ClaimCodeExpiryWorker} all collapse
 * to the identical {@link NotFoundException}/{@link ErrorCode#KH_CLM_0404} — an external caller
 * cannot distinguish "never existed" from "someone already claimed it." Unlike the throttle path
 * ({@code ClaimRedeemThrottleService}), none of these failures is audited individually (D7) —
 * logging every failed guess would turn an external scanner into a write amplifier against {@code
 * audit_log}.
 *
 * <p>This class is module-private (Modulith-enforced, not Java visibility — mirrors {@link
 * CredentialService}'s existing rationale, since {@code credential.web.ClaimController} in a
 * different sub-package of the same module needs to call it).
 */
@Service
public class ClaimRedemptionService {

  private final ClaimCodeRepository claimCodes;
  private final CredentialRepository credentials;
  private final ClaimsEncryptionService claimsEncryption;
  private final SchemaCatalog schemas;
  private final StatusListLookup statusLookup;
  private final AuditService audit;
  private final TenantDirectory tenants;

  public ClaimRedemptionService(
      ClaimCodeRepository claimCodes,
      CredentialRepository credentials,
      ClaimsEncryptionService claimsEncryption,
      SchemaCatalog schemas,
      StatusListLookup statusLookup,
      AuditService audit,
      TenantDirectory tenants) {
    this.claimCodes = claimCodes;
    this.credentials = credentials;
    this.claimsEncryption = claimsEncryption;
    this.schemas = schemas;
    this.statusLookup = statusLookup;
    this.audit = audit;
    this.tenants = tenants;
  }

  /**
   * Redeem a one-time claim code.
   *
   * @param rawCode the raw code exactly as the wallet scanned/received it
   * @return the credential material, decrypted once and never persisted again
   * @throws NotFoundException {@link ErrorCode#KH_CLM_0404} for every failure flavor (spec D5)
   */
  @Transactional
  public ClaimRedeemResult redeem(String rawCode) {
    ClaimCode claimCode =
        claimCodes.findByCodeHashForUpdate(sha256(rawCode)).orElseThrow(this::invalidOrExpired);

    Instant now = Instant.now();
    if (claimCode.getClaimedAt() != null
        || claimCode.getDisclosuresEnc() == null
        || claimCode.getExpiresAt().isBefore(now)) {
      throw invalidOrExpired();
    }

    byte[] plaintext = claimsEncryption.decrypt(claimCode.getDisclosuresEnc());
    String joined = new String(plaintext, StandardCharsets.UTF_8);
    List<String> disclosures = joined.isEmpty() ? List.of() : List.of(joined.split("~"));

    Credential credential =
        credentials
            .findById(claimCode.getCredentialId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "claim_code "
                            + claimCode.getId()
                            + " references a missing credential — the credential_id foreign key"
                            + " should make this impossible"));

    // D2: set claimed_at and zero disclosures_enc in the same statement group, before commit —
    // this is the platform's only copy of the plaintext disclosures, and it ends here.
    claimCode.setClaimedAt(now);
    claimCode.setDisclosuresEnc(null);
    claimCodes.save(claimCode);

    audit.record(AuditAction.CLAIM_CODE_REDEEMED, "credential", credential.getRef(), null);

    SchemaDetail schema =
        schemas
            .findDetailById(credential.getSchemaId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "credential "
                            + credential.getRef()
                            + " references a missing schema — the schema_id foreign key should"
                            + " make this impossible"));

    // KH-1.3 D7: the real public status-list URL (replacing the pre-KH-1.3 placeholder — the raw
    // status list id). The FK guarantees the list exists; an empty result is treated as impossible,
    // falling back to the id only defensively so a redeem never fails to ship a URI.
    String statusListUri =
        findRefForTenant(credential.getTenantId(), credential.getStatusListId())
            .map(StatusListRef::uri)
            .orElseGet(() -> credential.getStatusListId().toString());

    return new ClaimRedeemResult(
        credential.getRef(),
        credential.getSignedPayload(),
        disclosures,
        schema.id(),
        schema.nameI18n(),
        schema.version(),
        statusListUri,
        credential.getCreatedAt(),
        credential.getMaxUses(),
        credential.getValidTo());
  }

  private NotFoundException invalidOrExpired() {
    return new NotFoundException(ErrorCode.KH_CLM_0404, "error.clm.invalid_or_expired");
  }

  /**
   * Resolve a status list's reference under {@code tenantId}'s own {@link TenantContext}, not
   * whatever tenant happens to be ambient for the calling thread — KH-2.1 Part B: {@link #redeem}
   * runs under {@code SystemAccessExecutor} (no principal, no ambient tenant of its own), and
   * {@code status.domain.StatusListUriBuilder} builds the {@code /sl/{tenantSlug}/...} URL from
   * {@link TenantContext#currentSlug()} — without this, every redeem call would embed the {@code
   * statusListUri} using whichever tenant is ambient (the platform default in practice), even
   * though the credential itself belongs to a different tenant.
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

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm", e);
    }
  }
}
