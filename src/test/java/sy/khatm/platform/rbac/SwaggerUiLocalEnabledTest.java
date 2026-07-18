package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * With {@code local} active (merged onto {@link RbacHttpTestSupport}'s {@code test} profile —
 * {@code @ActiveProfiles} inherits by default), Swagger UI and the raw OpenAPI JSON must be
 * reachable with zero credentials: {@code rbac.security.SecurityConfig}'s local/dev carve-out, not
 * the D9 public-two list, is what's under test here.
 */
@ActiveProfiles("local")
class SwaggerUiLocalEnabledTest extends RbacHttpTestSupport {

  @Test
  void apiDocs_withLocalProfile_returns200AndParsesAsOpenApiJson() {
    ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"openapi\"").contains("Khatm Platform API");
  }

  @Test
  void swaggerUiHtml_withLocalProfile_returns200() {
    // springdoc redirects /swagger-ui.html -> /swagger-ui/index.html (302); the JDK HttpClient
    // backing this suite's TestRestTemplate (see RbacHttpTestSupport) does not auto-follow
    // redirects, so assert the rendered page at its final URL directly. What's under test here is
    // authorization (permitAll on the /swagger-ui/** matcher), not springdoc's own redirect.
    ResponseEntity<String> response = rest.getForEntity("/swagger-ui/index.html", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("swagger-ui");
  }
}
