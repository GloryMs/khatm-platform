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
 * Spec FS-2.1 D5 — {@link SystemAccessExecutor#runAsSystem} is callable only by the enumerated
 * services: redeem lookup, verify lookup, status-list read, JWKS read, API-key verification, the
 * two cross-tenant sweep workers, and (KH-2.6b, spec FS-2.5 §4) the org:admin aggregated-report
 * counter, the one audited path that reads another tenant's counts without switching {@code
 * TenantContext}. A source scan under {@code src/main/java}, same technique as {@code
 * TenantContextConstantAllowlistTest}/{@code shared.web.OpenApiContractTest}'s mapping-annotation
 * count — a new caller means updating this enumeration deliberately, not a silent addition.
 */
class SystemAccessCallerAllowlistTest {

  private static final Path SRC_MAIN = Path.of("").toAbsolutePath().resolve("src/main/java");
  private static final Pattern CALL_SITE = Pattern.compile("\\.runAsSystem\\(");

  /** Exactly the classes spec D5 enumerates — relative to {@code src/main/java}. */
  private static final Set<String> ALLOWED_FILES =
      Set.of(
          "sy/khatm/platform/credential/web/ClaimController.java", // redeem lookup
          "sy/khatm/platform/credential/web/CredentialController.java", // verify lookup
          "sy/khatm/platform/tenant/web/TenantStatusListController.java", // status-list read
          "sy/khatm/platform/tenant/web/TenantJwksController.java", // JWKS read
          "sy/khatm/platform/rbac/domain/ApiKeyService.java", // API-key verification (KH-2.1)
          "sy/khatm/platform/credential/worker/ClaimCodeExpiryWorker.java", // cross-tenant sweep
          "sy/khatm/platform/status/worker/StatusListPublishSweepWorker.java", // cross-tenant sweep
          "sy/khatm/platform/rbac/domain/OrgAdminService.java" // org:admin aggregated report
          );

  @Test
  void runAsSystem_isCalledOnlyByTheEnumeratedServices() throws IOException {
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
        .as("every runAsSystem call site must be in the spec FS-2.1 D5 enumeration")
        .containsExactlyInAnyOrderElementsOf(ALLOWED_FILES);
  }
}
