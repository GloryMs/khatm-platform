package sy.khatm.platform.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code khatm.public-base-url} (env {@code KHATM_PUBLIC_BASE_URL}) — the platform's
 * externally-reachable origin, the only source {@link PublicUrlBuilder} may use to build an
 * absolute self-referential URL.
 *
 * @param publicBaseUrl the externally-reachable base URL, e.g. {@code https://api.khatm.sy}; blank
 *     everywhere except the {@code local} profile (spec-equivalent no-silent-default pattern as
 *     {@code khatm.keys.soft.passphrase}/{@code khatm.claims.enc-key})
 */
@ConfigurationProperties(prefix = "khatm")
public record PublicUrlProperties(String publicBaseUrl) {}
