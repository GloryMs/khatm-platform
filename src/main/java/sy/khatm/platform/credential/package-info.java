/**
 * Credential module — core lifecycle of verifiable credentials.
 *
 * <p><b>Responsibilities:</b> issue, verify, consume (atomic single-use decrement), and revoke
 * credentials. Stores only cryptographic proofs and status metadata — never document content or PII
 * (P1 rule). Enforces the atomic-consume invariant: consumption is a single-transaction conditional
 * {@code UPDATE ... WHERE uses_remaining > 0 AND NOT revoked} so that exactly one consumer wins
 * under concurrent load.
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
 * sy.khatm.platform.key.api.KeySigner}) for JWT signing; {@code schema :: api}, {@code holder ::
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
