/**
 * Issuer-client module — machine-to-machine issuance identities (KH-2.8.1, spec FS-2.7a).
 *
 * <p><b>Responsibilities:</b> the {@code issuer_client} entity (an external system allowed to issue
 * credentials for one tenant), its {@code khi_} API key (generated, argon2id-hashed, shown once,
 * rotatable with a grace window), its lifecycle (create / rotate / suspend / resume / revoke), the
 * per-client schema allowlist, and the tenant-level holder HMAC secret provisioned into Vault KV on
 * a root tenant's first client (D9).
 *
 * <p><b>Exposed API:</b> {@code api/} sub-package — {@link
 * sy.khatm.platform.issuerclient.api.IssuerClientAuthenticator} (resolves a raw key to an {@link
 * sy.khatm.platform.issuerclient.api.IssuerClientPrincipal}, used by {@code rbac.security}'s
 * filter) and {@link sy.khatm.platform.issuerclient.api.IssuerClientSchemaAccess} (the per-client
 * schema allowlist check, used by {@code credential}).
 *
 * <p><b>Published events:</b> (none)
 *
 * <p><b>Tables owned:</b> {@code issuer_client}, {@code issuer_client_schema}. ({@code
 * credential.issuer_client_id} is a column of the {@code credential} module's table; this module
 * never reads or writes it.)
 *
 * <p><b>Cross-module dependencies:</b> {@code shared}, {@code shared :: audit}, {@code shared ::
 * error}, {@code tenant :: api} (hierarchy walk + status), {@code schema :: api} (allowlist
 * validation). Deliberately NOT {@code rbac}: {@code rbac} depends on this module's {@code api}, so
 * the reverse edge would be a cycle — the acting console user is read through {@code
 * shared.audit.AuditPrincipal}, and password hashing uses the plain {@code PasswordEncoder} bean.
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.issuerclient;
