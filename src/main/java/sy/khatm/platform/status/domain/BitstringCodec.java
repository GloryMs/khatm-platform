package sy.khatm.platform.status.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Bit-level operations on {@link StatusList#getBitstring()}, which is always stored gzip-compressed
 * (spec FS-1.3 D1/D4) — the same bytes {@code StatusListPublisher} base64url-encodes verbatim into
 * a signed artifact's {@code bits} claim, so no re-compression happens at publish time.
 *
 * <p>Bit order within a byte is MSB-first (bit {@code idx}'s byte is {@code idx / 8}, its position
 * within that byte is {@code idx % 8} counting from the most significant bit) — an internal
 * convention private to this codec; nothing outside it needs to agree on bit order since {@link
 * #flipBit} and {@link #isSet} are the only ways any caller ever touches a bit.
 *
 * <p>This class is module-private.
 */
final class BitstringCodec {

  private BitstringCodec() {}

  /**
   * Return a new gzip-compressed bitstring with bit {@code idx} set to 1. Flipping an already-set
   * bit is a harmless no-op on the bits themselves (spec FS-1.3 §3 — un-revoke does not exist, so
   * this method is only ever asked to set a bit, never clear one).
   */
  static byte[] flipBit(byte[] gzippedBitstring, int idx) {
    byte[] raw = inflate(gzippedBitstring);
    int byteIdx = idx / 8;
    int bitOffset = idx % 8;
    raw[byteIdx] = (byte) (raw[byteIdx] | (1 << (7 - bitOffset)));
    return deflate(raw);
  }

  /** Whether bit {@code idx} is set in the given gzip-compressed bitstring. */
  static boolean isSet(byte[] gzippedBitstring, int idx) {
    byte[] raw = inflate(gzippedBitstring);
    int byteIdx = idx / 8;
    int bitOffset = idx % 8;
    return (raw[byteIdx] & (1 << (7 - bitOffset))) != 0;
  }

  private static byte[] inflate(byte[] gzipped) {
    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
      return gzip.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to inflate status list bitstring", e);
    }
  }

  private static byte[] deflate(byte[] raw) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
      gzip.write(raw);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to deflate status list bitstring", e);
    }
    return buffer.toByteArray();
  }
}
