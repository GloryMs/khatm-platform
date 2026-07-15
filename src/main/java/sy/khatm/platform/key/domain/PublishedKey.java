package sy.khatm.platform.key.domain;

/**
 * A public JWK entry as published by {@link KeyLifecycleService#publishableKeys}, consumed by
 * {@code key/web/JwksController} (spec FS-0.5 §6).
 *
 * @param kid the key id
 * @param jwkJson the key's public JWK, JSON-serialized (no private material)
 */
public record PublishedKey(String kid, String jwkJson) {}
