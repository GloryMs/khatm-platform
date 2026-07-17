package sy.khatm.platform.rbac.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The platform's one password hasher (spec FS-0.6b D4): argon2id, via Spring Security's
 * BouncyCastle-backed {@link Argon2PasswordEncoder}, for every human console password (never for
 * API key secrets — {@code ApiKeyService} hashes those with SHA-256, D4 explains why the two need
 * different algorithms).
 */
@Configuration
class PasswordEncoderConfig {

  /**
   * {@link Argon2PasswordEncoder#defaultsForSpringSecurity_v5_8()} — Spring Security's own vetted
   * cost parameters, not hand-picked ones; this also keeps the platform automatically aligned with
   * whatever the framework's maintainers consider current best practice.
   *
   * @return the argon2id password encoder bean
   */
  @Bean
  PasswordEncoder passwordEncoder() {
    return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }
}
