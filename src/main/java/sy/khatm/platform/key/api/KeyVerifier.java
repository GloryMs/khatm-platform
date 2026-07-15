package sy.khatm.platform.key.api;

import java.util.Optional;

/**
 * SPI for resolving the public key material behind a {@code kid} — the verification half of the
 * {@code key} module's cross-module surface (the other is {@link KeySigner}).
 *
 * <p>Verification is deliberately split from signing: callers parse the JWT themselves, read its
 * {@code kid} header, ask this SPI to resolve that exact {@code kid}, and perform the cryptographic
 * check themselves against the returned {@link PublicKeyHandle}. This is what makes "resolve
 * strictly by {@code kid}, never fall back to the current active key" (FS-0.5 §4, SEC §3) a
 * property of the caller's own code path, not something the {@code key} module could silently
 * weaken later.
 *
 * <p>An unknown {@code kid}, or a {@code kid} belonging to a {@code RETIRED} key, both resolve to
 * {@link Optional#empty()} — callers must treat that as an unconditional signature failure, never
 * as "try the current key instead."
 */
public interface KeyVerifier {

  /**
   * Resolve the public key for a given {@code kid}.
   *
   * @param kid the key id from a JWS {@code kid} header; must not be {@code null}
   * @return the public key handle if {@code kid} identifies a known, non-{@code RETIRED} key;
   *     {@link Optional#empty()} otherwise (unknown {@code kid}, or a {@code RETIRED} one)
   */
  Optional<PublicKeyHandle> resolvePublicKey(String kid);
}
