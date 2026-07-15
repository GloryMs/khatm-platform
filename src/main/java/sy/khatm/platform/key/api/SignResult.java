package sy.khatm.platform.key.api;

/**
 * Result of a {@link KeySigner#sign} call.
 *
 * <p>{@code jws} is the complete compact JWS serialization (header.payload.signature), already
 * carrying the {@code kid} used in its protected header. {@code kid} and {@code algo} are exposed
 * alongside it so callers can log or assert on which key signed a token without re-parsing the JWS.
 *
 * @param kid the key id that signed this token, format {@code {tenant-slug}:key-{seq}}
 * @param algo the signing algorithm, currently always {@code ES256}
 * @param jws the compact JWS serialization
 */
public record SignResult(String kid, String algo, String jws) {}
