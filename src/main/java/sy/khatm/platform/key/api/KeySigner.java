package sy.khatm.platform.key.api;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;

/**
 * SPI for JWT signing — one half of the {@code key} module's cross-module surface (the other is
 * {@link KeyVerifier}).
 *
 * <p>This interface, together with {@link KeyVerifier}, is the <em>only</em> cross-module surface
 * of the {@code key} module (FS-0.5 §2, D1). The full key-management contract — {@code
 * KeyProvider}, with its {@code rotate()} and {@code keys()} operations — is module-private on
 * purpose: other modules must never see rotation, only "sign this."
 *
 * <p>Key material never crosses this boundary: callers pass claim sets in, receive a compact JWS
 * out. The {@code kid} header is chosen by the implementation automatically from whichever key is
 * currently {@code ACTIVE} for the tenant (SEC §3, §9).
 *
 * <p>Implementations are provided by the {@code key} module's domain layer and registered as Spring
 * beans, selected via {@code khatm.keys.provider} (FS-0.5 D3). Consumers inject this interface,
 * never a concrete implementation.
 */
public interface KeySigner {

  /**
   * Sign a JWT claims set using the current tenant's active issuer key (ES256).
   *
   * @param claims the claim set to sign; must not be {@code null}
   * @return the {@code kid}/algorithm/compact-JWS produced by the current active key
   * @throws JOSEException if signing fails due to a key or algorithm problem
   */
  SignResult sign(JWTClaimsSet claims) throws JOSEException;
}
