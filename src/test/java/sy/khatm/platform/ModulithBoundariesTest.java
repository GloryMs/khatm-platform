package sy.khatm.platform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * KH-0.1.2 — Verify Spring Modulith module boundaries.
 *
 * <p>This test uses Spring Modulith's static analysis to assert that:
 *
 * <ul>
 *   <li>Every detected package under {@code sy.khatm.platform} forms a valid module.
 *   <li>No module references internal types (non-{@code api} sub-packages) of another module.
 *   <li>Declared {@code allowedDependencies} in {@code @ApplicationModule} are respected.
 * </ul>
 *
 * <p>This test does <em>not</em> start a Spring application context — it is pure bytecode analysis
 * and runs without Docker / Testcontainers.
 *
 * <p>If this test fails after adding new code, check: (a) are you importing an internal type from
 * another module? If yes, either move the type to the other module's {@code api/} sub-package, or
 * use a Modulith application event to decouple.
 */
class ModulithBoundariesTest {

  @Test
  void moduleStructureIsValid() {
    ApplicationModules modules = ApplicationModules.of(KhatmPlatformApplication.class);
    modules.verify();
  }
}
