package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Spec FS-0.6b DoD #9 (first half), extended by spec FS-1.2.1 DoD #7 and spec FS-1.3 D9 — {@code
 * POST /api/v1/credentials/verify}, {@code GET /.well-known/jwks.json}, {@code POST
 * /api/v1/claims/redeem}, and {@code GET /sl/{tenantSlug}/{listCode}} work with <b>zero</b>
 * credentials: no session cookie, no {@code Authorization} header at all (the only four endpoints
 * that stay open).
 */
class PublicEndpointsNoCredentialsTest extends RbacHttpTestSupport {

  @Test
  void verify_withNoCredentialsAtAll_returns200() {
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/credentials/verify", Map.of("sdJwt", "not-a-real-jwt"), String.class);

    // A malformed presentation is still a *domain result* (spec FS-0.4/0.6a D1) — 200 valid:false,
    // never an auth error. The point here is specifically that no 401/403 occurs.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void jwks_withNoCredentialsAtAll_returns200() {
    ResponseEntity<String> response = rest.getForEntity("/.well-known/jwks.json", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void claimsRedeem_withNoCredentialsAtAll_returns404_notAnAuthError() {
    ResponseEntity<String> response =
        rest.postForEntity("/api/v1/claims/redeem", Map.of("code", "no-such-code"), String.class);

    // An unknown code is a domain-shaped KH-CLM-0404 (spec FS-1.2.1 D5), never 401/403 — the
    // point here is specifically that authentication is never even attempted for this path.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void statusList_withNoCredentialsAtAll_returns404_notAnAuthError() {
    ResponseEntity<String> response =
        rest.getForEntity("/sl/khatm-default/no-such-list-at-all", String.class);

    // An unknown list is a domain-shaped KH-STS-0404 (spec FS-1.3 D2), never 401/403 — the point
    // here is specifically that authentication is never even attempted for this path.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
