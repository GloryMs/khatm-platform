package sy.khatm.platform.issuerclient.domain;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Module wiring. The one bean here is the injectable {@link Clock} every time-dependent rule in
 * this module (rotation grace window, {@code expires_at}, the retiring sweep, auth-failure audit
 * throttling) reads — so tests can move time instead of sleeping (a test supplies a
 * {@code @Primary} {@link Clock}).
 */
@Configuration
class IssuerClientConfig {

  @Bean
  Clock issuerClientClock() {
    return Clock.systemUTC();
  }
}
