package sy.khatm.platform.key.domain;

import java.util.Optional;
import org.springframework.stereotype.Service;
import sy.khatm.platform.key.api.KeyVerifier;
import sy.khatm.platform.key.api.PublicKeyHandle;

/**
 * {@link KeyVerifier} implementation — delegates to {@link KeyLifecycleService}, which resolves
 * strictly by {@code kid} with no fallback of any kind (spec FS-0.5 §4).
 *
 * <p>Module-private; external callers depend on {@link KeyVerifier} only.
 */
@Service
class KeyVerifierImpl implements KeyVerifier {

  private final KeyLifecycleService lifecycle;

  KeyVerifierImpl(KeyLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Override
  public Optional<PublicKeyHandle> resolvePublicKey(String kid) {
    return lifecycle.resolvePublicKey(kid);
  }
}
