package sy.khatm.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Khatm platform — entry point for both {@code api} and {@code worker} runtime roles.
 *
 * <p>{@code @EnableScheduling} is global, but the only {@code @Scheduled} beans are worker-role
 * (gated by {@code khatm.worker.enabled} — the claim-code expiry sweep and the Redis Streams
 * poller), so scheduling is effectively a no-op in the {@code api} image.
 */
@SpringBootApplication
@EnableScheduling
public class KhatmPlatformApplication {
  public static void main(String[] args) {
    SpringApplication.run(KhatmPlatformApplication.class, args);
  }
}
