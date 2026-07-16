package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.4 §6 DoD #5 (encryption half) — {@code disclosures_enc} is genuinely AES-256-GCM encrypted:
 * a round trip with the right key recovers the plaintext, decryption with the wrong key fails, and
 * encrypting the same plaintext twice never produces the same ciphertext (fresh random nonce per
 * call, per D7). The fail-fast-startup half is {@code ClaimsEncryptionKeyFailureTest}.
 */
class ClaimsEncryptionServiceTest extends IntegrationTestSupport {

  @Autowired private ClaimsEncryptionService encryption;

  @Test
  void encrypt_thenDecrypt_roundTripsPlaintext() {
    byte[] plaintext = "d1~d2~d3".getBytes(StandardCharsets.UTF_8);

    byte[] ciphertext = encryption.encrypt(plaintext);
    byte[] decrypted = encryption.decrypt(ciphertext);

    assertThat(decrypted).isEqualTo(plaintext);
  }

  @Test
  void encrypt_calledTwiceWithSameInput_producesDifferentCiphertext() {
    byte[] plaintext = "same-disclosures-every-time".getBytes(StandardCharsets.UTF_8);

    byte[] first = encryption.encrypt(plaintext);
    byte[] second = encryption.encrypt(plaintext);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void decrypt_withWrongKey_fails() {
    byte[] plaintext = "secret-disclosures".getBytes(StandardCharsets.UTF_8);
    byte[] ciphertext = encryption.encrypt(plaintext);

    byte[] wrongKeyBytes = new byte[32];
    Arrays.fill(wrongKeyBytes, (byte) 0x42);
    String wrongKeyBase64 = Base64.getEncoder().encodeToString(wrongKeyBytes);
    ClaimsEncryptionService wrongKeyService =
        new ClaimsEncryptionService(wrongKeyBase64, new MockEnvironment());

    assertThatThrownBy(() -> wrongKeyService.decrypt(ciphertext))
        .isInstanceOf(IllegalStateException.class);
  }
}
