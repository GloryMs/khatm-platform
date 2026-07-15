package sy.khatm.platform.key.domain;

/**
 * Result of {@link KeyProvider#generate}.
 *
 * @param kid the key id the new key pair was registered under
 * @param publicJwkJson the public JWK, JSON-serialized (no private material — safe to persist
 *     verbatim into {@code issuer_key.public_jwk})
 * @param providerRef opaque backend reference to the private key (for {@link SoftKeyProvider}, the
 *     keystore alias, always equal to {@code kid})
 */
record GeneratedKeyMaterial(String kid, String publicJwkJson, String providerRef) {}
