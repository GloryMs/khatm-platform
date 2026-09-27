package sy.khatm.platform.issuerclient.domain;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Generates, parses, and verifies {@code khi_<prefix>_<secret>} issuer-client keys (spec FS-2.7a
 * D2).
 *
 * <p>The prefix is ten lowercase base32 characters (lookup handle, safe to log); the secret is 32
 * {@link SecureRandom} bytes, base64url without padding (43 characters). Only the secret is hashed
 * — with the platform's argon2id {@link PasswordEncoder} — and only the prefix (without the {@code
 * khi_} tag) and the hash are ever stored.
 */
@Component
class ApiKeyGenerator {

  static final String TAG = "khi_";
  static final int PREFIX_LENGTH = 10;
  static final int SECRET_LENGTH = 43;
  private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";
  private static final String BASE64URL =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final PasswordEncoder encoder;
  private volatile byte[] dummyHash;

  ApiKeyGenerator(PasswordEncoder encoder) {
    this.encoder = encoder;
  }

  /** A freshly generated key: the raw value (shown once) plus what may be persisted. */
  record Generated(String rawKey, String prefix, byte[] secretHash) {}

  /** A syntactically valid presented key split into its parts. */
  record Parsed(String prefix, String secret) {}

  Generated generate() {
    StringBuilder prefix = new StringBuilder(PREFIX_LENGTH);
    for (int i = 0; i < PREFIX_LENGTH; i++) {
      prefix.append(BASE32.charAt(RANDOM.nextInt(BASE32.length())));
    }
    byte[] secretBytes = new byte[32];
    RANDOM.nextBytes(secretBytes);
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    String rawKey = TAG + prefix + "_" + secret;
    byte[] hash = encoder.encode(secret).getBytes(StandardCharsets.UTF_8);
    return new Generated(rawKey, prefix.toString(), hash);
  }

  /**
   * Split a presented value, rejecting anything not exactly the generated shape before any hashing
   * is attempted (a malformed value never costs an argon2 evaluation).
   */
  static Optional<Parsed> parse(String rawKey) {
    if (rawKey == null || !rawKey.startsWith(TAG)) {
      return Optional.empty();
    }
    String rest = rawKey.substring(TAG.length());
    if (rest.length() != PREFIX_LENGTH + 1 + SECRET_LENGTH || rest.charAt(PREFIX_LENGTH) != '_') {
      return Optional.empty();
    }
    String prefix = rest.substring(0, PREFIX_LENGTH);
    String secret = rest.substring(PREFIX_LENGTH + 1);
    for (int i = 0; i < prefix.length(); i++) {
      if (BASE32.indexOf(prefix.charAt(i)) < 0) {
        return Optional.empty();
      }
    }
    for (int i = 0; i < secret.length(); i++) {
      if (BASE64URL.indexOf(secret.charAt(i)) < 0) {
        return Optional.empty();
      }
    }
    return Optional.of(new Parsed(prefix, secret));
  }

  boolean matches(String secret, byte[] storedHash) {
    return encoder.matches(secret, new String(storedHash, StandardCharsets.UTF_8));
  }

  /**
   * Burn one argon2 evaluation against a throwaway hash — called when the prefix is unknown so an
   * unknown key costs the same wall-clock time as a known one with a wrong secret.
   */
  void spendEqualTime(String secret) {
    byte[] hash = dummyHash;
    if (hash == null) {
      hash =
          encoder.encode("khatm-issuer-client-timing-equalizer").getBytes(StandardCharsets.UTF_8);
      dummyHash = hash;
    }
    matches(secret, hash);
  }
}
