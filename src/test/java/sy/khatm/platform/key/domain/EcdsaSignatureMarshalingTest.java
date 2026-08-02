package sy.khatm.platform.key.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.impl.ECDSA;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import org.junit.jupiter.api.Test;

/**
 * Session brief (spec FS-2.3 D5): "verify the raw-vs-DER encoding difference explicitly with a test
 * vector" — for {@link VaultTransitProvider#normalizeToRawJoseSignature}, the code that defensively
 * handles a Vault response whose {@code marshaling_algorithm} did not, for whatever reason,
 * actually apply.
 *
 * <p>No Vault container needed: a real EC P-256 key pair is generated in-process, signed via plain
 * JCA ({@code Signature.getInstance("SHA256withECDSA")}, which — like Vault's own default — always
 * produces ASN.1 DER, never the fixed-length raw format JWS/ES256 requires), and the resulting DER
 * bytes are the test vector.
 */
class EcdsaSignatureMarshalingTest {

  private static final byte[] MESSAGE = "khatm-vault-signature-marshaling-test".getBytes();

  @Test
  void derSignature_isNotAlreadyRawLength_theEncodingDifferenceThisTestVectorProves()
      throws Exception {
    KeyPair keyPair = generateEcKeyPair();
    byte[] der = signDer(keyPair.getPrivate(), MESSAGE);

    int rawLength = ECDSA.getSignatureByteArrayLength(JWSAlgorithm.ES256);
    // The whole reason normalizeToRawJoseSignature's defensive check exists: a real DER-encoded
    // P-256 signature (SEQUENCE + two variable-length INTEGERs, ~70-72 bytes with ASN.1 overhead)
    // is essentially never exactly 64 bytes, unlike JOSE's fixed-length r||s concatenation.
    assertThat(der.length).isNotEqualTo(rawLength);
    assertThat(der[0]).as("DER signatures open with the SEQUENCE tag 0x30").isEqualTo((byte) 0x30);
  }

  @Test
  void normalizeToRawJoseSignature_derInput_transcodesToRawLength_andStillVerifies()
      throws Exception {
    KeyPair keyPair = generateEcKeyPair();
    byte[] der = signDer(keyPair.getPrivate(), MESSAGE);

    byte[] raw = VaultTransitProvider.normalizeToRawJoseSignature(der);

    int expectedRawLength = ECDSA.getSignatureByteArrayLength(JWSAlgorithm.ES256);
    assertThat(raw).hasSize(expectedRawLength);
    assertThat(verifiesAsJoseSignature(raw, (ECPublicKey) keyPair.getPublic())).isTrue();
  }

  @Test
  void normalizeToRawJoseSignature_alreadyRawInput_passesThroughUnchanged() throws Exception {
    KeyPair keyPair = generateEcKeyPair();
    byte[] der = signDer(keyPair.getPrivate(), MESSAGE);
    byte[] rawFromDer =
        ECDSA.transcodeSignatureToConcat(
            der, ECDSA.getSignatureByteArrayLength(JWSAlgorithm.ES256));

    byte[] normalized = VaultTransitProvider.normalizeToRawJoseSignature(rawFromDer);

    assertThat(normalized).isEqualTo(rawFromDer);
    assertThat(verifiesAsJoseSignature(normalized, (ECPublicKey) keyPair.getPublic())).isTrue();
  }

  private static KeyPair generateEcKeyPair() throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
    kpg.initialize(new ECGenParameterSpec("secp256r1"));
    return kpg.generateKeyPair();
  }

  private static byte[] signDer(java.security.PrivateKey privateKey, byte[] message)
      throws Exception {
    Signature signature = Signature.getInstance("SHA256withECDSA");
    signature.initSign((ECPrivateKey) privateKey);
    signature.update(message);
    return signature.sign();
  }

  private static boolean verifiesAsJoseSignature(byte[] rawSignature, ECPublicKey publicKey)
      throws Exception {
    ECKey jwk = new ECKey.Builder(Curve.P_256, publicKey).build();
    ECDSAVerifier verifier = new ECDSAVerifier(jwk);
    // ECDSAVerifier.verify(header, signedContent, signature) expects the raw JOSE signature —
    // exactly the shape normalizeToRawJoseSignature is responsible for producing.
    com.nimbusds.jose.JWSHeader header =
        new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.ES256).build();
    return verifier.verify(header, MESSAGE, com.nimbusds.jose.util.Base64URL.encode(rawSignature));
  }
}
