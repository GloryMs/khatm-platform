package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Swagger UI / OpenAPI JSON must stay behind authentication outside {@code local}/{@code dev} —
 * this suite runs under plain {@code test} (see {@link RbacHttpTestSupport}), so a request with no
 * session/API key must 401 exactly like any other protected endpoint (session-role default
 * fallback: {@code rbac.security.SecurityConfig#configureAuthorization}).
 */
class SwaggerUiAccessTest extends RbacHttpTestSupport {

  @Test
  void apiDocs_withNoLocalOrDevProfile_returns401() {
    ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void swaggerUiHtml_withNoLocalOrDevProfile_returns401() {
    ResponseEntity<String> response = rest.getForEntity("/swagger-ui.html", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
