package sy.khatm.platform.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata (title, version, security schemes) for the springdoc-generated {@code
 * /v3/api-docs} contract and the Swagger UI built on top of it (local/dev only — see {@code
 * rbac.security.SecurityConfig}).
 *
 * <p>{@code project.version} is resolved from {@code application.yml}'s {@code @project.version@}
 * placeholder, filled in by Maven resource filtering (already enabled for {@code application.yml}
 * by the inherited {@code spring-boot-starter-parent} POM) — no build-plugin change needed.
 *
 * <p><b>Security schemes (KH-1.1.3, C2b docs gap):</b> the published contract previously declared
 * no {@code components.securitySchemes} at all, so an integrator reading {@code
 * docs/api/openapi.json} alone had no way to learn the {@code Authorization: Bearer khk_...} header
 * from the contract itself — {@code rbac.security.SecurityConfig}'s Javadoc was the only place that
 * documented it. Two schemes, matching the platform's two real auth mechanisms ({@code
 * rbac.security.SecurityConfig}'s two {@code SecurityFilterChain}s): {@code sessionCookie} (the
 * {@code KHATM_SESSION} cookie a console login sets) and {@code apiKeyBearer} (the {@code khk_...}
 * bearer token). Declared as scheme definitions only — no per-operation {@code security}
 * requirement is wired on individual endpoints this session (an explicit, deliberate scope
 * decision, documented in {@code docs/STATE.md}: springdoc's per-operation security annotations
 * would need auditing every endpoint's actual auth story individually, which is more than this
 * additive docs-gap fix needs). Purely additive to the contract — no existing path or schema
 * changes.
 */
@Configuration
class OpenApiConfig {

  @Bean
  OpenAPI khatmOpenApi(@Value("${project.version}") String version) {
    return new OpenAPI()
        .info(new Info().title("Khatm Platform API").version(version))
        .components(
            new Components()
                .addSecuritySchemes(
                    "sessionCookie",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("KHATM_SESSION")
                        .description(
                            "Console session cookie, set by POST /api/v1/auth/login. Used"
                                + " together with the XSRF-TOKEN cookie/X-XSRF-TOKEN header on"
                                + " state-changing requests."))
                .addSecuritySchemes(
                    "apiKeyBearer",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("khk_...")
                        .description(
                            "API key bearer token: Authorization: Bearer khk_<prefix>.<secret>."
                                + " Scope and actor-kind requirements vary per endpoint — see each"
                                + " operation's description.")));
  }
}
