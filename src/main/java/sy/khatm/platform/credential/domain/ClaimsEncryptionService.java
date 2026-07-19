package sy.khatm.platform.credential.domain;

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
 * AES-256-GCM encryption for {@code claim_code.disclosures_enc} (spec FS-0.4 D7).
 *
 * <p>The disclosures generated at issuance time are the one and only plaintext copy of a
 * credential's claim values that ever exists outside the holder's own wallet — they are never
 * written to {@code credential.signed_payload} (P1) and must never sit unencrypted in {@code
 * claim_code} either, even for the short window between issuance and claim. This class exists so
 * that window never has a plaintext gap.
 *
 * <p>The key comes from {@code khatm.claims.enc-key} (env-sourced in real deployments — {@code
 * KHATM_CLAIMS_ENC_KEY}, 32 raw bytes, base64-encoded). Outside the {@code local} profile, a
 * missing key fails startup immediately — mirrors {@code SoftKeyProvider}'s passphrase pattern
 * (FS-0.5 §3) exactly, for the same reason: silently proceeding without one is a worse failure mode
 * than refusing to start.
 *
 * <p>Each {@link #encrypt} call generates a fresh random 96-bit nonce and prepends it to the
 * ciphertext, so {@link #decrypt} never needs the nonce supplied separately (spec FS-0.4 D7: "nonce
 * عشوائي لكل صف يُخزَّن مع الـ ciphertext").
 *
 * <p>This class is module-private.
 */
@Component
class ClaimsEncryptionService {

  private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final int GCM_NONCE_LENGTH_BYTES = 12;
  private static final int KEY_LENGTH_BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  ClaimsEncryptionService(@Value("${khatm.claims.enc-key:}") String encKeyBase64, Environment env) {
    if ((encKeyBase64 == null || encKeyBase64.isBlank())
        && !env.acceptsProfiles(Profiles.of("local"))) {
      throw new IllegalStateException(
          "khatm.claims.enc-key is required outside the 'local' profile — set the "
              + "KHATM_CLAIMS_ENC_KEY environment variable (32 raw bytes, base64-encoded). "
              + "Refusing to start without a claims encryption key.");
    }

    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(encKeyBase64 == null ? "" : encKeyBase64);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("khatm.claims.enc-key is not valid base64.", e);
    }
    if (keyBytes.length != KEY_LENGTH_BYTES) {
      throw new IllegalStateException(
          "khatm.claims.enc-key must decode to exactly "
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
      throw new IllegalStateException("Failed to encrypt claim disclosures.", e);
    }
  }

  /**
   * Decrypt a value produced by {@link #encrypt}.
   *
   * <p>Called by {@link ClaimRedemptionService#redeem} (spec FS-1.2.1) — the only production path
   * that ever needs the plaintext disclosures back, exactly once, inside the transaction that
   * immediately zeroes {@code disclosures_enc} afterward.
   *
   * @param nonceAndCiphertext {@code nonce (12 bytes) || ciphertext-with-GCM-tag}, as produced by
   *     {@link #encrypt}
   * @return the original plaintext
   * @throws IllegalStateException if decryption fails (wrong key, corrupted data, or tampering —
   *     GCM's authentication tag makes these indistinguishable, which is the point)
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
      throw new IllegalStateException("Failed to decrypt claim disclosures.", e);
    }
  }
}
