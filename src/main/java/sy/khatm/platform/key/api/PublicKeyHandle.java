package sy.khatm.platform.key.api;

import java.security.interfaces.ECPublicKey;

/**
 * A public key resolved by {@link KeyVerifier#resolvePublicKey}, ready to build a JCA/JOSE verifier
 * from.
 *
 * <p>Only ever carries public material — see {@code SoftKeyProvider} for the guarantee that private
 * key material never crosses the {@code key} module boundary.
 *
 * @param kid the key id this handle was resolved for
 * @param algo the signing algorithm this key is used with, currently always {@code ES256}
 * @param publicKey the EC public key (P-256), usable to construct e.g. an {@code ECDSAVerifier}
 */
public record PublicKeyHandle(String kid, String algo, ECPublicKey publicKey) {}
