package sy.khatm.platform.credential.domain;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Platform-generated {@code holderRef} values (KH-2.8.2, spec FS-2.7a veto V1-b).
 *
 * <p>A human console session (channel C — an issuer with no system of its own to compute the
 * connector's HMAC) may omit {@code holderRef}; the platform then assigns 32 random bytes as 64
 * lowercase hex, the same shape the connector contract (D8) requires. The value deliberately links
 * to no one: it is unique per credential, not derived from any person, so two credentials of the
 * same citizen cannot be correlated through it. Machine callers never get one generated.
 */
public final class HolderRefs {

  private static final SecureRandom RANDOM = new SecureRandom();

  private HolderRefs() {}

  /**
   * A fresh random holder reference.
   *
   * @return 64 lowercase hex characters from 32 {@link SecureRandom} bytes
   */
  public static String random() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
