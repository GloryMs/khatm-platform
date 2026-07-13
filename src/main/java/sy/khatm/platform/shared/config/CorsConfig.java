package sy.khatm.platform.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration.
 *
 * <p><b>WARNING — permissive for local development only.</b> Allows any origin so that the Vite dev
 * server and mobile emulators can call the API without proxy setup. This must be locked down to
 * explicit origin allowlists before any production deployment.
 */
@Configuration
class CorsConfig implements WebMvcConfigurer {

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/**")
        .allowedOriginPatterns("*")
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*");
  }
}
