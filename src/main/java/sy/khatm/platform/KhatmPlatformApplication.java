package sy.khatm.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Khatm platform — entry point for both {@code api} and {@code worker} runtime roles. */
@SpringBootApplication
public class KhatmPlatformApplication {
  public static void main(String[] args) {
    SpringApplication.run(KhatmPlatformApplication.class, args);
  }
}
