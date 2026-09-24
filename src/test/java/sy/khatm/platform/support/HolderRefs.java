package sy.khatm.platform.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Test-side builder of valid {@code holderRef} values (spec FS-2.7a D8: exactly 64 lowercase hex
 * characters — the shape of a SHA-256 HMAC). Every issuance path enforces that contract since
 * KH-2.8.1, so tests that used to pass free-form labels ("holder-1") derive a conforming value from
 * the label instead, keeping each test's intent readable.
 */
public final class HolderRefs {

  private HolderRefs() {}

  /** A deterministic valid holderRef for {@code label}. */
  public static String of(String label) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(label.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm", e);
    }
  }

  /** A valid holderRef unique to this call (label plus a random UUID) — for isolated fixtures. */
  public static String unique(String label) {
    return of(label + UUID.randomUUID());
  }
}
