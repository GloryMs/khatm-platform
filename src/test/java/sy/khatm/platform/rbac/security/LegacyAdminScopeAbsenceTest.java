package sy.khatm.platform.rbac.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Spec FS-2.2 D1/V3 — the coarse {@code admin} scope is scrubbed from the codebase entirely (clean
 * cut, no coexistence with the granular registry). A source scan under {@code src/main/java}, same
 * technique as {@code shared.SystemAccessCallerAllowlistTest} — no live code may pass the literal
 * string {@code "admin"} as a scope value ever again; {@link ScopeRegistry} is the only legal
 * source of scope literals from here on.
 */
class LegacyAdminScopeAbsenceTest {

  private static final Path SRC_MAIN = Path.of("").toAbsolutePath().resolve("src/main/java");

  /**
   * The exact quoted string literal a scope value would take — {@code "admin"}, not a substring.
   */
  private static final Pattern ADMIN_SCOPE_LITERAL = Pattern.compile("\"admin\"");

  /** This class's own Javadoc names the retired literal by way of explaining this very test. */
  private static final String SELF_EXEMPT_FILE = "ScopeRegistry.java";

  @Test
  void noSourceFile_stillPassesTheLiteralAdminScopeString() throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(SRC_MAIN)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        if (SELF_EXEMPT_FILE.equals(file.getFileName().toString())) {
          continue;
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (ADMIN_SCOPE_LITERAL.matcher(content).find()) {
          violations.add(SRC_MAIN.relativize(file).toString().replace('\\', '/'));
        }
      }
    }

    assertThat(violations)
        .as(
            "source files still passing the literal string \"admin\" as a scope value — the"
                + " retired coarse scope (spec FS-2.2 V3); use a sy.khatm.platform.rbac.security"
                + ".ScopeRegistry constant instead")
        .isEmpty();
  }
}
