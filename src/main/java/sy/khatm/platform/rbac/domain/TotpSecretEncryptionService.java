package sy.khatm.platform.rbac.domain;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for {@code app_user.totp_secret_enc} (spec FS-2.2 V1).
 *
 * <p>Same recipe as {@code credential.domain.ClaimsEncryptionService} (algorithm, GCM tag length,
 * random-nonce-prepended-to-ciphertext convention) — that class is module-private to {@code
 * credential} and cannot be imported from {@code rbac} without either promoting it to {@code
 * credential :: api} (an odd, unrelated addition to that module's narrow public surface) or
 * reaching across the module boundary directly (a Modulith violation). A second small class with
 * its own dedicated key is the smaller, safer change — a TOTP secret and claim disclosures are
 * different security domains and should not share one encryption key regardless.
 *
 * <p>The key comes from {@code khatm.auth.totp.enc-key} (env-sourced in real deployments — {@code
 * KHATM_AUTH_TOTP_ENC_KEY}, 32 raw bytes, base64-encoded). Same no-silent-default pattern as {@code
 * SoftKeyProvider}'s passphrase / {@code ClaimsEncryptionService}'s own key.
 *
 * <p>This class is module-private.
 */
@Component
class TotpSecretEncryptionService {

  private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final int GCM_NONCE_LENGTH_BYTES = 12;
  private static final int KEY_LENGTH_BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  TotpSecretEncryptionService(
      @Value("${khatm.auth.totp.enc-key:}") String encKeyBase64, Environment env) {
    if ((encKeyBase64 == null || encKeyBase64.isBlank())
        && !env.acceptsProfiles(Profiles.of("local"))) {
      throw new IllegalStateException(
          "khatm.auth.totp.enc-key is required outside the 'local' profile — set the "
              + "KHATM_AUTH_TOTP_ENC_KEY environment variable (32 raw bytes, base64-encoded). "
              + "Refusing to start without a TOTP secret encryption key.");
    }

    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(encKeyBase64 == null ? "" : encKeyBase64);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("khatm.auth.totp.enc-key is not valid base64.", e);
    }
    if (keyBytes.length != KEY_LENGTH_BYTES) {
      throw new IllegalStateException(
          "khatm.auth.totp.enc-key must decode to exactly "
              + KEY_LENGTH_BYTES
              + " bytes (AES-256); got "
              + keyBytes.length
              + ".");
    }
    this.key = new SecretKeySpec(keyBytes, "AES");
  }

  /**
   * Encrypt {@code plaintext} with a freshly generated random nonce, returned prepended to the
   * ciphertext.
   *
   * @param plaintext the bytes to encrypt; never logged, never returned as-is
   * @return {@code nonce (12 bytes) || ciphertext-with-GCM-tag}
   */
  byte[] encrypt(byte[] plaintext) {
    byte[] nonce = new byte[GCM_NONCE_LENGTH_BYTES];
    RANDOM.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
      byte[] ciphertext = cipher.doFinal(plaintext);
      byte[] result = new byte[nonce.length + ciphertext.length];
      System.arraycopy(nonce, 0, result, 0, nonce.length);
      System.arraycopy(ciphertext, 0, result, nonce.length, ciphertext.length);
      return result;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to encrypt TOTP secret.", e);
    }
  }

  /**
   * Decrypt a value produced by {@link #encrypt}.
   *
   * @param nonceAndCiphertext {@code nonce (12 bytes) || ciphertext-with-GCM-tag}, as produced by
   *     {@link #encrypt}
   * @return the original plaintext secret bytes
   * @throws IllegalStateException if decryption fails (wrong key, corrupted data, or tampering)
   */
  byte[] decrypt(byte[] nonceAndCiphertext) {
    byte[] nonce = Arrays.copyOfRange(nonceAndCiphertext, 0, GCM_NONCE_LENGTH_BYTES);
    byte[] ciphertext =
        Arrays.copyOfRange(nonceAndCiphertext, GCM_NONCE_LENGTH_BYTES, nonceAndCiphertext.length);
    try {
      Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
      return cipher.doFinal(ciphertext);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to decrypt TOTP secret.", e);
    }
  }
}
