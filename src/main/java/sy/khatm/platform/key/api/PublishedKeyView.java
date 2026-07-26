package sy.khatm.platform.key.api;

/**
 * Cross-module view of one publishable JWKS entry (spec FS-2.1 D8) — mirrors {@code
 * key.domain.PublishedKey} (module-private) for callers outside the {@code key} module.
 *
 * @param kid the key's id
 * @param jwkJson the public JWK, JSON-serialized
 */
public record PublishedKeyView(String kid, String jwkJson) {}
