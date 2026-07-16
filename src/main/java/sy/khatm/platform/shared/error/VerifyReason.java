package sy.khatm.platform.shared.error;

/**
 * The vocabulary of credential-presentation verification outcomes (spec FS-0.6a D2).
 *
 * <p>A verification failure (bad signature, tampered disclosure, withheld mandatory claim,
 * revocation, expiry...) is a <em>domain result</em>, not an API error — {@code
 * CredentialService#verify} always returns a {@code VerifyResponse} with one of these codes, never
 * throws (spec FS-0.6a D1). This is a deliberately separate registry from {@link ErrorCode}
 * (different vocabulary, different HTTP-status story — every {@code VerifyReason} rides on a 200)
 * but both are translated through the same {@code MessageSource} channel: a code's key is {@code
 * verify.reason.<code>}.
 *
 * <p>{@link #UNKNOWN_KID} is split out from what was previously a single generic "bad signature"
 * outcome: a {@code kid} that cannot be resolved at all (missing header, unknown, or {@code
 * RETIRED} — spec FS-0.5 §4) is a materially different situation from a resolved key whose
 * signature bytes simply don't verify, and the spec's own D2 vocabulary lists them separately.
 */
public enum VerifyReason {
  /**
   * The presentation is fully valid: signature, disclosures, and mandatory claims all check out.
   */
  VALID("valid"),

  /**
   * The signature is valid but no local record exists for this credential's {@code ref} — still
   * authentic (e.g. offline-issued), just unknown to this platform instance.
   */
  VALID_SIGNATURE_UNKNOWN_REF("valid_signature_unknown_ref"),

  /** A {@code kid} was resolved but the signature bytes do not verify against its public key. */
  BAD_SIGNATURE("bad_signature"),

  /** The JWS {@code kid} header is missing, unknown, or belongs to a {@code RETIRED} key. */
  UNKNOWN_KID("unknown_kid"),

  /** The credential's {@code exp} claim is in the past. */
  EXPIRED("expired"),

  /** The credential has been explicitly revoked. */
  REVOKED("revoked"),

  /** The payload's {@code _sd_alg} is not {@code sha-256} (spec FS-0.4 D8). */
  BAD_SD_ALG("bad_sd_alg"),

  /** A presented disclosure's digest is not in the payload's {@code _sd} array. */
  FORGED_DISCLOSURE("forged_disclosure"),

  /** The same claim name was disclosed more than once in one presentation. */
  DUPLICATE_DISCLOSURE("duplicate_disclosure"),

  /** A {@code claims_def} field outside {@code sd_fields} (mandatory) was not disclosed. */
  WITHHELD_MANDATORY_CLAIM("withheld_mandatory_claim"),

  /** The presented value is not a well-formed compact JWT / SD-JWT at all. */
  MALFORMED("malformed");

  private final String code;

  VerifyReason(String code) {
    this.code = code;
  }

  /**
   * The wire-format reason code carried in {@code VerifyResponse.reason} (unchanged since KH-0.4,
   * e.g. {@code "forged_disclosure"}).
   *
   * @return the snake_case reason code
   */
  public String code() {
    return code;
  }

  /**
   * The {@code MessageSource} key this reason's localized {@code reasonMessage} resolves from.
   *
   * @return the dot-notation message key
   */
  public String messageKey() {
    return "verify.reason." + code;
  }
}
