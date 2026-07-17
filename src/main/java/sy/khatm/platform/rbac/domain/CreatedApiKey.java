package sy.khatm.platform.rbac.domain;

import java.util.UUID;

/**
 * The one-time result of {@link ApiKeyService#create} — {@code rawKey} is the only moment the
 * secret ever exists outside the caller's own storage; the platform never persists it (spec FS-0.6b
 * §4).
 *
 * @param id the created key's id (used for later revocation)
 * @param keyPrefix the key's lookup prefix — safe to display/log going forward
 * @param rawKey the full {@code khk_<env>_<prefix>.<secret>} value — show this to the caller
 *     exactly once and never again
 */
public record CreatedApiKey(UUID id, String keyPrefix, String rawKey) {}
