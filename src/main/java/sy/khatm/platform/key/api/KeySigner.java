package sy.khatm.platform.key.api;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * SPI for JWT signing and signature verification.
 *
 * <p>This is the <em>only</em> cross-module surface of the key module. All other modules that need
 * cryptographic operations depend on this interface — never on a concrete implementation.
 *
 * <p>Key material never crosses this boundary: callers pass claim sets in, receive compact JWS
 * strings out. The {@code kid} header is set by the implementation automatically (SEC §9).
 *
 * <p>Implementations are provided by the {@code key} module's domain layer and registered as Spring
 * beans. Consumers inject this interface, never the implementation class.
 */
public interface KeySigner {

  /**
   * Sign a JWT claims set using the current active issuer key (ES256).
   *
   * @param claims the claim set to sign; must not be {@code null}
   * @return the compact serialization of the resulting JWS (three Base64URL segments)
   * @throws JOSEException if signing fails due to a key or algorithm problem
   */
  String sign(JWTClaimsSet claims) throws JOSEException;

  /**
   * Verify the JWS signature on an already-parsed JWT.
   *
   * <p>This is the path used by online verification: the platform checks the token against its own
   * current public key. Offline verifiers fetch the public key from {@link #publicKeyPem()} or
   * {@link #jwksJson()} and verify locally.
   *
   * @param jwt a parsed, potentially signed JWT; must not be {@code null}
   * @return {@code true} if the signature is valid for the current key; {@code false} otherwise
   */
  boolean verifySignature(SignedJWT jwt);

  /**
   * Return the current public JWKS as a JSON string (public key only, no private material).
   *
   * <p>Verifiers cache this response to validate signatures without calling back to the platform.
   *
   * @return JSON representation of the public JWK Set
   */
  String jwksJson();

  /**
   * Return the current public key in PEM-encoded PKCS#8 format.
   *
   * <p>Used by mobile wallets that prefer PEM over JWKS for on-device offline verification.
   *
   * @return PEM string starting with {@code -----BEGIN PUBLIC KEY-----}
   */
  String publicKeyPem();
}
