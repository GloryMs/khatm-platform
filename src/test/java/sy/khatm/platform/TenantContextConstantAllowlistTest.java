package sy.khatm.platform;

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
 * Spec FS-2.1 D1: {@code shared.TenantContext.DEFAULT_TENANT_ID}/{@code DEFAULT_TENANT_SLUG} must
 * stay legal only in seeders and the constant's own definition — every runtime call site resolves
 * the tenant via {@code TenantContext.current()}/{@code currentSlug()} instead (which itself falls
 * back to the default when no request-scoped value has been set, so seeders/the {@code local}
 * profile keep working unchanged).
 *
 * <p>A source scan under {@code src/main/java}, same technique as {@code
 * shared.web.OpenApiContractTest}'s mapping-annotation count — deliberately no Spring context,
 * stays fast. As of KH-2.1 there are zero violations; this is a regression gate against a future
 * session reaching for the constant directly instead of the resolved-context accessor.
 */
class TenantContextConstantAllowlistTest {

  private static final Path SRC_MAIN = Path.of("").toAbsolutePath().resolve("src/main/java");
  private static final Pattern CONSTANT_REFERENCE =
      Pattern.compile("TenantContext\\.(DEFAULT_TENANT_ID|DEFAULT_TENANT_SLUG)\\b");

  @Test
  void onlyTenantContextItselfAndSeeders_referenceTheDefaultTenantConstants() throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(SRC_MAIN)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        if (isAllowed(file)) {
          continue;
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (CONSTANT_REFERENCE.matcher(content).find()) {
          violations.add(SRC_MAIN.relativize(file).toString());
        }
      }
    }

    assertThat(violations)
        .as(
            "TenantContext.DEFAULT_TENANT_ID/DEFAULT_TENANT_SLUG referenced outside the allowed"
                + " packages (shared/TenantContext.java itself, **/seed/**) — call"
                + " TenantContext.current()/currentSlug() instead (spec FS-2.1 D1)")
        .isEmpty();
  }

  /** The constant's own definition, and any file under a {@code seed} package (spec D1). */
  private static boolean isAllowed(Path file) {
    if (file.getFileName().toString().equals("TenantContext.java")) {
      return true;
    }
    for (Path segment : file) {
      if ("seed".equals(segment.toString())) {
        return true;
      }
    }
    return false;
  }
}
