package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.authlete.sd.SDJWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.api.VerifyResponse;
import sy.khatm.platform.key.api.KeySigner;
import sy.khatm.platform.rbac.RbacHttpTestSupport;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.tenant.api.TenantAdmin;
import sy.khatm.platform.tenant.api.TenantView;

/**
 * FS-0.4 Amendment A1 (D3-a) — the {@code jwks_uri} claim baked into every issued SD-JWT: correct
 * per-tenant value (non-default and default), a real HTTP round trip through {@code
 * tenant.web.TenantJwksController} closing the loop that broke on staging (wallet "Signed by
 * unrecognized key"), and proof that platform-side verification never depends on the field's
 * presence (backward compatibility with pre-A1 credentials).
 *
 * <p>Extends {@code rbac.RbacHttpTestSupport} for a real embedded HTTP server — the same
 * cross-module test-support reuse already established by {@code credential.web
 * .ClaimControllerHttpTest} and {@code tenant.web.StatusListControllerHttpTest}.
 */
class TenantJwksUriIssuanceHttpTest extends RbacHttpTestSupport {

  @Autowired private TenantAdmin admin;
  @Autowired private CredentialService credentialService;
  @Autowired private KeySigner keySigner;
  @Autowired private SystemAccessExecutor systemAccess;

  private static String uniqueSlug(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private TenantView createTenant(String prefix) {
    return admin.create(
        uniqueSlug(prefix),
        new LocalizedText(prefix + " tenant", prefix + " مستأجر"),
        "OTHER",
        null);
  }

  private IssueResponse issueUnder(UUID tenantId, String tenantSlug) {
    TenantContext.set(tenantId, tenantSlug);
    try {
      return credentialService.issue(
          new IssueRequest("GenericDocument/v1", "jwks-e2e-holder", 1, 60, Map.of(), null, null));
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void issue_nonDefaultTenant_bakesJwksUri_matchingTemplateAndSlug() throws Exception {
    TenantView tenant = createTenant("jwks-value");

    IssueResponse issued = issueUnder(tenant.id(), tenant.slug());
    String compactJwt = SDJWT.parse(issued.sdJwt()).getCredentialJwt();
    JWTClaimsSet claims = SignedJWT.parse(compactJwt).getJWTClaimsSet();

    assertThat(claims.getStringClaim("jwks_uri"))
        .isEqualTo("http://localhost:8080/t/" + tenant.slug() + "/.well-known/jwks.json");
  }

  @Test
  void issue_defaultTenant_bakesJwksUri_forDefaultSlug() throws Exception {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "GenericDocument/v1", "jwks-default-holder", 1, 60, Map.of(), null, null));

    String compactJwt = SDJWT.parse(issued.sdJwt()).getCredentialJwt();
    JWTClaimsSet claims = SignedJWT.parse(compactJwt).getJWTClaimsSet();

    assertThat(claims.getStringClaim("jwks_uri"))
        .isEqualTo(
            "http://localhost:8080/t/"
                + TenantContext.DEFAULT_TENANT_SLUG
                + "/.well-known/jwks.json");
  }

  @Test
  void issue_nonDefaultTenant_bakedJwksUri_actuallyServesTheSigningKid() throws Exception {
    TenantView tenant = createTenant("jwks-fetch");

    IssueResponse issued = issueUnder(tenant.id(), tenant.slug());
    String compactJwt = SDJWT.parse(issued.sdJwt()).getCredentialJwt();
    SignedJWT parsed = SignedJWT.parse(compactJwt);
    String kid = parsed.getHeader().getKeyID();
    String jwksUri = parsed.getJWTClaimsSet().getStringClaim("jwks_uri");

    // khatm.public-base-url is fixed to http://localhost:8080 in this suite (RbacHttpTestSupport),
    // independent of the real random port the embedded server listens on for this test — only the
    // path shape is under test, re-rooted against the test's own client instead of the baked host.
    String path = URI.create(jwksUri).getRawPath();
    ResponseEntity<String> response = rest.getForEntity(path, String.class);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).contains(kid);
  }

  @Test
  void verify_preA1CredentialWithoutJwksUri_stillVerifiesUnchanged() throws Exception {
    TenantView tenant = createTenant("jwks-legacy");
    IssueResponse issued = issueUnder(tenant.id(), tenant.slug());

    // Mirrors CredentialController#verify's own wrapping (spec FS-2.1 D5): issuer_key is
    // RLS-protected, and this test's ambient TenantContext (nothing set => default) does not
    // match the credential's own tenant, exactly like TenantHierarchyLineageVerifyTest.
    VerifyResponse before =
        systemAccess.runAsSystem(() -> credentialService.verify(issued.sdJwt()));
    assertThat(before.valid()).isTrue();

    // Simulate a pre-Amendment-A1 presentation: strip jwks_uri and re-sign with the same tenant's
    // active key (disclosures/_sd are untouched) — otherwise byte-identical to what issue()
    // produced before this session, proving the platform's own verification never depends on it.
    String[] parts = issued.sdJwt().split("~", -1);
    JWTClaimsSet original = SignedJWT.parse(parts[0]).getJWTClaimsSet();
    Map<String, Object> legacyPayload = new LinkedHashMap<>(original.toJSONObject());
    assertThat(legacyPayload.remove("jwks_uri")).isNotNull();

    TenantContext.set(tenant.id(), tenant.slug());
    String legacyCompactJwt;
    try {
      legacyCompactJwt = keySigner.sign(JWTClaimsSet.parse(legacyPayload)).jws();
    } finally {
      TenantContext.clear();
    }
    StringBuilder legacyPresentation = new StringBuilder(legacyCompactJwt);
    for (int i = 1; i < parts.length; i++) {
      legacyPresentation.append('~').append(parts[i]);
    }

    String legacyPresentationValue = legacyPresentation.toString();
    VerifyResponse after =
        systemAccess.runAsSystem(() -> credentialService.verify(legacyPresentationValue));

    assertThat(after.valid()).isTrue();
    assertThat(after.reason()).isEqualTo(before.reason());
  }
}
