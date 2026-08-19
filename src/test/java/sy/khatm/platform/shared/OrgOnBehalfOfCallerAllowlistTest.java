package sy.khatm.platform.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Spec FS-2.5 §3 — {@link OnBehalfOfExecutor#runAsChildOrg} is callable only by the enumerated
 * org:admin call site: {@code rbac.domain.OrgAdminService}, which every child-targeted operation
 * (user list/create/disable/reset-password, schema view, suspend/activate) routes through after
 * validating the target is a genuine direct child of the caller's own tenant. Same source-scan
 * technique as {@code OnBehalfOfCallerAllowlistTest}/{@code SystemAccessCallerAllowlistTest} — a
 * new caller means updating this enumeration deliberately, not a silent addition.
 */
class OrgOnBehalfOfCallerAllowlistTest {

  private static final Path SRC_MAIN = Path.of("").toAbsolutePath().resolve("src/main/java");
  private static final Pattern CALL_SITE = Pattern.compile("\\.runAsChildOrg\\(");

  private static final Set<String> ALLOWED_FILES =
      Set.of("sy/khatm/platform/rbac/domain/OrgAdminService.java");

  @Test
  void runAsChildOrg_isCalledOnlyByTheEnumeratedCallSites() throws IOException {
    List<String> callers = new ArrayList<>();
    try (Stream<Path> files = Files.walk(SRC_MAIN)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (CALL_SITE.matcher(content).find()) {
          String relative = SRC_MAIN.relativize(file).toString().replace('\\', '/');
          callers.add(relative);
        }
      }
    }

    assertThat(callers)
        .as("every runAsChildOrg call site must be in the spec FS-2.5 §3 enumeration")
        .containsExactlyInAnyOrderElementsOf(ALLOWED_FILES);
  }
}
