/**
 * Credential module — core lifecycle of verifiable credentials.
 *
 * <p><b>Responsibilities:</b> issue, verify, consume (atomic single-use decrement), and revoke
 * credentials. Stores only cryptographic proofs and status metadata — never document content or PII
 * (P1 rule). Enforces the atomic-consume invariant: consumption is a single-transaction conditional
 * {@code UPDATE ... WHERE uses_remaining > 0 AND NOT revoked} so that exactly one consumer wins
 * under concurrent load.
 *
 * <p><b>SD-JWT (spec FS-0.4):</b> issuance builds a Selective Disclosure JWT, not a plain JWT. Two
 * decisions are worth calling out specifically because they change what "P1" and {@code sd_fields}
 * mean in this module now:
 *
 * <ul>
 *   <li><b>D1 — every {@code claims_def} field becomes a disclosure, no exceptions.</b> There is no
 *       "this one claim is fine to leave explicit" escape hatch. If even one field stayed explicit
 *       in the signed payload, its value would land in {@code credential.signed_payload} — a P1
 *       violation (FS-0.2 D9) baked permanently into a stored, signed artifact. Hiding everything
 *       is the only form that keeps P1 a structural property of the token rather than a
 *       storage-policy promise someone could get wrong later.
 *   <li><b>D2 — {@code sd_fields} no longer means "hidden fields."</b> Since D1 already hides every
 *       field, {@code credential_schema.sd_fields} (unchanged column) is redefined to mean "fields
 *       the holder is <em>permitted to withhold</em> at presentation time." Every {@code
 *       claims_def} field <em>not</em> in {@code sd_fields} is mandatory — {@link
 *       sy.khatm.platform.credential.domain.CredentialService#verify} rejects (reason {@code
 *       withheld_mandatory_claim}) any presentation missing one. This gives the issuer a "mandatory
 *       minimum" (e.g. document number and name always shown; birth date optional).
 * </ul>
 *
 * <p><b>Exposed API:</b> {@code api/} sub-package — DTO records only at this stage. Cross-module
 * service interface will be added when another module requires programmatic access (KH-1.x).
 *
 * <p><b>Published events:</b> {@code CredentialIssued}, {@code CredentialConsumed}, {@code
 * CredentialRevoked} (future — KH-1.3)
 *
 * <p><b>Tables owned:</b> {@code credential}, {@code consumption_event}, {@code claim_code}
 *
 * <p><b>Cross-module dependencies:</b> {@code key :: api} ({@link
 * sy.khatm.platform.key.api.KeySigner} for signing, {@link sy.khatm.platform.key.api.KeyVerifier}
 * for strict-by-{@code kid} verification, no fallback); {@code schema :: api}, {@code holder ::
 * api}, {@code status :: api}, {@code consumer :: api} — issuing/consuming a credential must
 * resolve the schema, holder, status-list allocation, and consuming party its foreign keys point at
 * (KH-0.2.1 baseline schema, spec FS-0.2 §3.6/§3.9); {@code shared} (its open root package — {@link
 * sy.khatm.platform.shared.TenantContext}, {@link sy.khatm.platform.shared.Uuidv7}).
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
      "key :: api",
      "schema :: api",
      "holder :: api",
      "status :: api",
      "consumer :: api",
      "shared"
    })
package sy.khatm.platform.credential;
