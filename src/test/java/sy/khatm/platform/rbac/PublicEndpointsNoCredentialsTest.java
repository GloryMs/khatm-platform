package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Spec FS-0.6b DoD #9 (first half) — {@code POST /api/v1/credentials/verify} and {@code GET
 * /.well-known/jwks.json} work with <b>zero</b> credentials: no session cookie, no {@code
 * Authorization} header at all (D9 — the only two endpoints that stay open).
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
}
