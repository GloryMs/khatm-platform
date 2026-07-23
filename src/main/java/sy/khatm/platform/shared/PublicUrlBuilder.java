package sy.khatm.platform.shared;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * The single place any module turns a server-relative path into an absolute, externally-reachable
 * URL a client outside this process — a wallet, a verifier — can actually dereference.
 *
 * <p>Deliberately never derives a host from the incoming request ({@code
 * ServletUriComponentsBuilder}, {@code HttpServletRequest#getRequestURL()}): a request may arrive
 * via a reverse proxy, a container-network hostname, or (locally) {@code localhost}, none of which
 * a phone on the LAN or a real wallet can resolve. The only source of truth is {@code
 * khatm.public-base-url}.
 *
 * <p>Outside the {@code local} profile, {@code khatm.public-base-url} has no default and startup
 * fails immediately if it is blank — same no-silent-default pattern as {@code
 * khatm.keys.soft.passphrase} ({@code SoftKeyProvider}) and {@code khatm.claims.enc-key} ({@code
 * ClaimsEncryptionService}).
 */
@Component
@EnableConfigurationProperties(PublicUrlProperties.class)
public class PublicUrlBuilder {

  private final String baseUrl;

  PublicUrlBuilder(PublicUrlProperties properties, Environment env) {
    String raw = properties.publicBaseUrl();
    if ((raw == null || raw.isBlank()) && !env.acceptsProfiles(Profiles.of("local"))) {
      throw new IllegalStateException(
          "khatm.public-base-url is required outside the 'local' profile — set the "
              + "KHATM_PUBLIC_BASE_URL environment variable to this server's externally reachable "
              + "base URL. Refusing to start and emit self-referential URLs a client could not "
              + "resolve.");
    }
    // Trim a trailing slash so a misconfigured "http://host:8080/" doesn't produce "//sl/...".
    this.baseUrl = (raw == null || raw.isBlank()) ? "" : stripTrailingSlash(raw);
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /**
   * Build an absolute URL for {@code path}, rooted at the configured public base URL.
   *
   * @param path a server-relative path, with or without a leading slash
   * @return {@code khatm.public-base-url + "/" + path}
   */
  public String build(String path) {
    String normalized = path.startsWith("/") ? path : "/" + path;
    return baseUrl + normalized;
  }
}
