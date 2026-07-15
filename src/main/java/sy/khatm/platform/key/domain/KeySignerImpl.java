package sy.khatm.platform.key.domain;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.stereotype.Service;
import sy.khatm.platform.key.api.KeySigner;
import sy.khatm.platform.key.api.SignResult;
import sy.khatm.platform.shared.TenantContext;

/**
 * {@link KeySigner} implementation — delegates to {@link KeyLifecycleService}, which resolves the
 * current tenant's {@code ACTIVE} key and performs the signing through whichever {@link
 * KeyProvider} is configured (spec FS-0.5 §2).
 *
 * <p>Module-private; external callers depend on {@link KeySigner} only.
 */
@Service
class KeySignerImpl implements KeySigner {

  private final KeyLifecycleService lifecycle;

  KeySignerImpl(KeyLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Override
  public SignResult sign(JWTClaimsSet claims) throws JOSEException {
    return lifecycle.signWithActiveKey(TenantContext.current(), claims);
  }
}
