package sy.khatm.platform.rbac.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.util.encoders.Base32;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 30-second step, 6 digits) — the standard almost every authenticator app
 * (Google Authenticator, Authy, 1Password, …) implements, so a hand-rolled, dependency-free
 * implementation here is exactly as interoperable as a library would be and needs no new entry on
 * the frozen stack (CLAUDE.md). Secrets are encoded/decoded as Base32 (RFC 4648) for the {@code
 * otpauth://} URI, via {@code org.bouncycastle.util.encoders.Base32} — {@code bcprov-jdk18on} is
 * already a pinned dependency (used for {@code Argon2BytesGenerator}), so this adds no new library
 * either.
 *
 * <p>Module-private static utility — {@link TotpService} is the only caller.
 */
final class TotpAlgorithm {

  private static final int SECRET_LENGTH_BYTES = 20; // 160 bits, RFC 4226 §4's recommended length
  private static final int TIME_STEP_SECONDS = 30;
  private static final int DIGITS = 6;
  private static final String HMAC_ALGORITHM = "HmacSHA1";
  private static final SecureRandom RANDOM = new SecureRandom();

  private TotpAlgorithm() {}

  /** Generate a fresh random secret (160 bits). */
  static byte[] generateSecret() {
    byte[] secret = new byte[SECRET_LENGTH_BYTES];
    RANDOM.nextBytes(secret);
    return secret;
  }

  /**
   * Base32-encode a secret for display/URI use (no padding stripped — most apps tolerate either).
   */
  static String toBase32(byte[] secret) {
    return new String(Base32.encode(secret), StandardCharsets.US_ASCII);
  }

  /**
   * Build a standard {@code otpauth://totp/...} enrollment URI (accepted by every mainstream
   * authenticator app via QR code or manual entry).
   *
   * @param issuer the platform/tenant label shown in the app (e.g. {@code Khatm})
   * @param accountName the user-identifying label shown in the app (e.g. {@code tenant:username})
   * @param secret the raw secret this URI encodes
   * @return the {@code otpauth://} URI
   */
  static String buildOtpAuthUri(String issuer, String accountName, byte[] secret) {
    String label = urlEncode(issuer) + ":" + urlEncode(accountName);
    return "otpauth://totp/"
        + label
        + "?secret="
        + toBase32(secret)
        + "&issuer="
        + urlEncode(issuer)
        + "&algorithm=SHA1&digits="
        + DIGITS
        + "&period="
        + TIME_STEP_SECONDS;
  }

  /**
   * Verify a submitted code against {@code secret}, tolerating ±1 time-step of clock drift (a
   * ~30s-either-side window, the standard, widely-implemented TOTP allowance).
   *
   * @param secret the raw (decrypted) secret
   * @param code the submitted code, digits only
   * @param epochSeconds the current time, as epoch seconds (a parameter, not {@code Instant.now()},
   *     so tests can pin specific time steps deterministically)
   * @return {@code true} if {@code code} matches any of the previous/current/next time step
   */
  static boolean verify(byte[] secret, String code, long epochSeconds) {
    if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
      return false;
    }
    long currentStep = epochSeconds / TIME_STEP_SECONDS;
    for (long step = currentStep - 1; step <= currentStep + 1; step++) {
      if (code.equals(generateCode(secret, step))) {
        return true;
      }
    }
    return false;
  }

  private static String generateCode(byte[] secret, long timeStep) {
    byte[] counter = new byte[8];
    for (int i = 7; i >= 0; i--) {
      counter[i] = (byte) (timeStep & 0xFF);
      timeStep >>>= 8;
    }
    byte[] hash = hmacSha1(secret, counter);
    int offset = hash[hash.length - 1] & 0x0F;
    int binary =
        ((hash[offset] & 0x7F) << 24)
            | ((hash[offset + 1] & 0xFF) << 16)
            | ((hash[offset + 2] & 0xFF) << 8)
            | (hash[offset + 3] & 0xFF);
    int truncated = binary % 1_000_000; // 10^DIGITS
    return String.format("%06d", truncated);
  }

  private static byte[] hmacSha1(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
      return mac.doFinal(data);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("Failed to compute TOTP HMAC.", e);
    }
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
