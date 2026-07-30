package sy.khatm.platform.support;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.util.encoders.Base32;

/**
 * Test-side RFC 6238 TOTP code generator — plays the role of a real authenticator app against a
 * Base32 secret returned by {@code POST /users/me/totp/enroll}. Deliberately a separate, standalone
 * implementation of the same public RFC 6238 algorithm {@code rbac.domain.TotpAlgorithm} uses in
 * production (module-private, unreachable from test packages outside {@code rbac.domain}) — this is
 * the "act as an external authenticator app" side of the standard, not a reimplementation of any
 * internal business logic that could silently drift from production behavior.
 *
 * <p>Lives in {@code support} (not {@code rbac}) so any test package that needs to complete a
 * mandatory-2FA login (spec FS-2.2 V1) can use it — {@code rbac.SessionTestSupport} and {@code
 * db.CrossTenantIsolationTest} both do.
 */
public final class TotpTestCodes {

  private static final int TIME_STEP_SECONDS = 30;
  private static final String HMAC_ALGORITHM = "HmacSHA1";

  private TotpTestCodes() {}

  /** The current valid 6-digit code for {@code base32Secret}, using the current wall-clock time. */
  public static String currentCode(String base32Secret) {
    byte[] secret = Base32.decode(base32Secret.getBytes(StandardCharsets.US_ASCII));
    long timeStep = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
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
    return String.format("%06d", binary % 1_000_000);
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
}
