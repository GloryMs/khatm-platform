package sy.khatm.platform.shared.web;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only fixture: a deterministic trigger for {@code GlobalExceptionHandler}'s catch-all path
 * (spec FS-0.6a §5 DoD #1's "synthetic 500" comparison). Never shipped — this class lives entirely
 * under {@code src/test/java}, so it is not on the production classpath regardless of the
 * {@code @Profile} guard; the guard just keeps it out of every test context too except the ones
 * that actually want it (all of them run under {@code test}).
 */
@RestController
@Profile("test")
class TestBoomController {

  @GetMapping("/api/v1/_test/boom")
  String boom() {
    throw new IllegalStateException(
        "boom - simulated internal failure for testing, never sent to a client");
  }
}
