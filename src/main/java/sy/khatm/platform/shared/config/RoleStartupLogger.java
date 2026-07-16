package sy.khatm.platform.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs the active runtime role ({@code api} or {@code worker}) once the context is ready, so the
 * startup output states unambiguously which image role is running (ADR-09: one image, two roles
 * selected by Spring profile).
 *
 * <p>Active in both roles. The role value comes from {@code khatm.role}, set by the {@code api}/
 * {@code worker} profile documents in {@code application.yml} (default {@code api}).
 */
@Component
public class RoleStartupLogger {

  private static final Logger log = LoggerFactory.getLogger(RoleStartupLogger.class);

  private final String role;

  public RoleStartupLogger(@Value("${khatm.role:api}") String role) {
    this.role = role;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    log.info(
        "Khatm platform ready — runtime role={} "
            + "(api: publishes events, no stream consumers; worker: consumes, no business REST)",
        role);
  }
}
