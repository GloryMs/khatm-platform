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
 * Spec FS-2.2 D4 — {@link OnBehalfOfExecutor#runAsTenant} is callable only by the enumerated
 * cross-tenant admin call sites. Today that is exactly one: minting a TENANT API key for a tenant
 * other than the caller's own ({@code rbac.web.AuthController#createApiKey}'s explicit-{@code
 * tenantId} branch) — the one endpoint shared by a self-service (@{@code tenant:admin}) caller and
 * a cross-tenant (@{@code platform:admin}) caller, so the authorization split can only live in
 * code, never a URL-pattern {@code SecurityConfig} rule. {@code
 * tenant.domain.TenantAdminService#create} deliberately does NOT go through this class — {@code
 * /api/v1/admin/tenants/**} is already {@code platform:admin}-exclusive at the HTTP boundary with
 * no other caller, so an in-service check there would be pure redundancy (see that class's own
 * Javadoc for the full rationale, including why it would break {@code
 * tenant.domain.TenantAdminServiceTest}'s no-HTTP service-level tests). A source scan under {@code
 * src/main/java}, the same technique {@code shared.SystemAccessCallerAllowlistTest} already
 * established — a new caller means updating this enumeration deliberately, not a silent addition.
 */
class OnBehalfOfCallerAllowlistTest {

  private static final Path SRC_MAIN = Path.of("").toAbsolutePath().resolve("src/main/java");
  private static final Pattern CALL_SITE = Pattern.compile("\\.runAsTenant\\(");

  /** Exactly the call sites spec FS-2.2 D4 covers — relative to {@code src/main/java}. */
  private static final Set<String> ALLOWED_FILES =
      Set.of(
          // Minting a TENANT API key for a tenant other than the caller's own.
          "sy/khatm/platform/rbac/web/AuthController.java");

  @Test
  void runAsTenant_isCalledOnlyByTheEnumeratedCallSites() throws IOException {
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
        .as("every runAsTenant call site must be in the spec FS-2.2 D4 enumeration")
        .containsExactlyInAnyOrderElementsOf(ALLOWED_FILES);
  }
}
