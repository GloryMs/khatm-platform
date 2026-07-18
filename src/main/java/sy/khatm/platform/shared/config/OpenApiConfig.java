package sy.khatm.platform.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata (title, version) for the springdoc-generated {@code /v3/api-docs}
 * contract and the Swagger UI built on top of it (local/dev only — see {@code
 * rbac.security.SecurityConfig}).
 *
 * <p>{@code project.version} is resolved from {@code application.yml}'s {@code @project.version@}
 * placeholder, filled in by Maven resource filtering (already enabled for {@code application.yml}
 * by the inherited {@code spring-boot-starter-parent} POM) — no build-plugin change needed.
 */
@Configuration
class OpenApiConfig {

  @Bean
  OpenAPI khatmOpenApi(@Value("${project.version}") String version) {
    return new OpenAPI().info(new Info().title("Khatm Platform API").version(version));
  }
}
