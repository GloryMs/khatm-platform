package sy.khatm.poc.key;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.interfaces.ECPublicKey;
import java.util.Base64;

/**
 * Holds the issuer key pair and does the crypto.
 * DEMO: an ephemeral EC P-256 key is generated at startup and kept in memory.
 * PRODUCTION: load from an HSM / KMS and never expose the private key.
 */
@Service
public class KeyService {

    @Value("${khatm.key-id:khatm-key-1}")
    private String keyId;

    private ECKey ecJwk;         // private + public
    private String publicPem;    // cached PEM of the public key

    @PostConstruct
    void init() throws JOSEException {
        this.ecJwk = new ECKeyGenerator(Curve.P_256)
                .keyID(keyId)
                .generate();
        this.publicPem = toPem(ecJwk.toECPublicKey());
    }

    /** Sign a claims set as a compact JWS (ES256). */
    public String sign(JWTClaimsSet claims) throws JOSEException {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(keyId)
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new ECDSASigner(ecJwk));
        return jwt.serialize();
    }

    /** Verify signature only (this is what an offline verifier does). */
    public boolean verifySignature(SignedJWT jwt) {
        try {
            return jwt.verify(new ECDSAVerifier(ecJwk.toPublicJWK()));
        } catch (JOSEException e) {
            return false;
        }
    }

    /** Public JWKS as JSON (public key only). */
    public String jwksJson() {
        return new JWKSet(ecJwk.toPublicJWK()).toString();
    }

    /** Public key in PEM — the mobile wallet caches this once for offline verify. */
    public String publicKeyPem() {
        return publicPem;
    }

    private static String toPem(ECPublicKey pub) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pub.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }
}
