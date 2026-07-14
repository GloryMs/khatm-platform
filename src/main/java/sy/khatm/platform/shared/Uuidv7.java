package sy.khatm.platform.shared;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates time-ordered UUIDv7 identifiers (RFC 9562), the primary-key convention for every table
 * in the platform (spec FS-0.2 §2 D1).
 *
 * <p>Ordering the 48-bit millisecond timestamp in the high bits keeps B-tree index locality for
 * primary keys generated at high volume — unlike UUIDv4, sequential inserts land near each other on
 * disk instead of scattering randomly across the index.
 */
public final class Uuidv7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Uuidv7() {}

  /**
   * Generate a new UUIDv7 value using the current wall-clock time.
   *
   * @return a fresh, time-ordered UUID
   */
  public static UUID generate() {
    long millis = Instant.now().toEpochMilli();
    byte[] value = new byte[16];

    value[0] = (byte) (millis >>> 40);
    value[1] = (byte) (millis >>> 32);
    value[2] = (byte) (millis >>> 24);
    value[3] = (byte) (millis >>> 16);
    value[4] = (byte) (millis >>> 8);
    value[5] = (byte) millis;

    byte[] random = new byte[10];
    RANDOM.nextBytes(random);
    System.arraycopy(random, 0, value, 6, 10);

    value[6] = (byte) ((value[6] & 0x0F) | 0x70); // version 7
    value[8] = (byte) ((value[8] & 0x3F) | 0x80); // variant 10

    long msb = 0;
    for (int i = 0; i < 8; i++) {
      msb = (msb << 8) | (value[i] & 0xFF);
    }
    long lsb = 0;
    for (int i = 8; i < 16; i++) {
      lsb = (lsb << 8) | (value[i] & 0xFF);
    }
    return new UUID(msb, lsb);
  }
}
