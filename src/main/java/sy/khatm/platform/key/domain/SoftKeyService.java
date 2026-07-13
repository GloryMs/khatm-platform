package sy.khatm.platform.key.domain;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sy.khatm.platform.key.api.KeySigner;

/**
 * Software-based {@link KeySigner} that generates an ephemeral EC P-256 key at startup.
 *
 * <p><b>WARNING — DEMO / DEV ONLY.</b> The key is in-memory and is regenerated on every restart.
 * Any JWT issued in a previous run will fail signature verification after a restart. Production
 * deployments must replace this with a KMS/HSM-backed implementation (KH-0.5).
 *
 * <p>This class is module-private. External callers must depend on {@link KeySigner}, not this
 * class.
 */
@Service
class SoftKeyService implements KeySigner {

  @Value("${khatm.key-id:khatm-key-1}")
  private String keyId;

  private ECKey ecJwk;
  private String publicPem;

  @PostConstruct
  void init() throws JOSEException {
    this.ecJwk = new ECKeyGenerator(Curve.P_256).keyID(keyId).generate();
    this.publicPem = toPem(ecJwk.toECPublicKey());
  }

  @Override
  public String sign(JWTClaimsSet claims) throws JOSEException {
    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).type(JOSEObjectType.JWT).build();
    SignedJWT jwt = new SignedJWT(header, claims);
    jwt.sign(new ECDSASigner(ecJwk));
    return jwt.serialize();
  }

  @Override
  public boolean verifySignature(SignedJWT jwt) {
    try {
      return jwt.verify(new ECDSAVerifier(ecJwk.toPublicJWK()));
    } catch (JOSEException e) {
      return false;
    }
  }

  @Override
  public String jwksJson() {
    return new JWKSet(ecJwk.toPublicJWK()).toString();
  }

  @Override
  public String publicKeyPem() {
    return publicPem;
  }

  private static String toPem(ECPublicKey pub) {
    String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pub.getEncoded());
    return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
  }
}
