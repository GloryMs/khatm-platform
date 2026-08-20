package sy.khatm.platform.credential.domain;

import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.PublicUrlBuilder;
import sy.khatm.platform.shared.TenantContext;

/**
 * Builds the fully-qualified public per-tenant JWKS discovery URL (spec FS-0.4 Amendment A1, D3-a):
 * {@code {khatm.public-base-url}/t/{tenantSlug}/.well-known/jwks.json}.
 *
 * <p>Mirrors {@code status.domain.StatusListUriBuilder}'s shape exactly: same base-URL source
 * ({@link PublicUrlBuilder}), same {@link TenantContext#currentSlug()} source of the issuing
 * tenant. Baked into every issued credential's {@code jwks_uri} claim by {@link CredentialService}
 * — pure discovery metadata for external verifiers, never an input to the platform's own {@code kid
 * -> KeyVerifier} trust decision (D3-a). Module-private: consumed only at issuance.
 */
@Component
class TenantJwksUriBuilder {

  private final PublicUrlBuilder publicUrlBuilder;

  TenantJwksUriBuilder(PublicUrlBuilder publicUrlBuilder) {
    this.publicUrlBuilder = publicUrlBuilder;
  }

  String build() {
    return publicUrlBuilder.build("/t/" + TenantContext.currentSlug() + "/.well-known/jwks.json");
  }
}
