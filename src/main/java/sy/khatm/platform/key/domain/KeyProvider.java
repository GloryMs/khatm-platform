package sy.khatm.platform.key.domain;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Optional;
import sy.khatm.platform.key.api.PublicKeyHandle;
import sy.khatm.platform.key.api.SignResult;

/**
 * SPI for the physical cryptographic backend behind an issuer key (spec FS-0.5 §2/§3, D3).
 *
 * <p>This is deliberately scoped to raw crypto operations against an opaque {@code providerRef} —
 * it knows nothing about tenants, {@code issuer_key} rows, or lifecycle states. That split is what
 * makes D3 ("swap SOFT for KMS/PKCS11 = a config change, zero code") actually true: a future {@code
 * KmsProvider} implements exactly this interface and {@link KeyLifecycleService}, which owns all
 * persistence and state-machine logic, does not change at all.
 *
 * <p>Module-private. Each implementation registers itself under a name (bean name {@code "SOFT"}/
 * {@code "VAULT"}, gated by its own {@code @ConditionalOnProperty}) — {@link KeyLifecycleService}
 * holds every registered implementation in a name-keyed map (spec FS-2.3 D5/D6, KH-2.3b) rather
 * than injecting a single one, and resolves the right one per {@code issuer_key} row's own stored
 * {@code provider} value, never a single ambient default. See {@link SoftKeyProvider} (always
 * registered) and {@link VaultTransitProvider} (registered only when {@code
 * khatm.keys.vault.enabled=true}).
 */
interface KeyProvider {

  /**
   * Generate a new EC P-256 key pair in the backend and register it under {@code kid}.
   *
   * @param kid the key id the new key pair will be known by (also used as the provider-internal
   *     reference for {@link SoftKeyProvider}, since its keystore alias equals the {@code kid})
   * @return the generated key's public JWK (JSON) and opaque provider reference
   */
  GeneratedKeyMaterial generate(String kid);

  /**
   * Sign a JWT claims set with the private key referenced by {@code providerRef}.
   *
   * @param providerRef opaque backend reference to the private key, as returned by {@link
   *     #generate}
   * @param kid the key id to stamp into the JWS {@code kid} header
   * @param claims the claim set to sign
   * @return the signed result, carrying {@code kid}, algorithm, and compact JWS
   * @throws JOSEException if signing fails
   */
  SignResult sign(String providerRef, String kid, JWTClaimsSet claims) throws JOSEException;

  /**
   * Resolve the public key behind a provider reference.
   *
   * @param providerRef opaque backend reference, as returned by {@link #generate}
   * @param kid the key id to stamp into the returned handle
   * @return the public key handle, or {@link Optional#empty()} if {@code providerRef} is unknown to
   *     this backend
   */
  Optional<PublicKeyHandle> publicKey(String providerRef, String kid);
}
