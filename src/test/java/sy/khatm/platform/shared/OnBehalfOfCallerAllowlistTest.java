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
 * cross-tenant admin call sites. Today that is exactly two:
 *
 * <ul>
 *   <li>{@code rbac.web.AuthController#createApiKey}'s explicit-{@code tenantId} branch — minting a
 *       TENANT API key for a tenant other than the caller's own; the one endpoint shared by a
 *       self-service ({@code tenant:admin}) caller and a cross-tenant ({@code platform:admin})
 *       caller, so the authorization split can only live in code, never a URL-pattern {@code
 *       SecurityConfig} rule.
 *   <li>{@code rbac.domain.TenantProvisioningService} (KH-2.2b, spec FS-2.2 D6) — onboarding a
 *       tenant's role catalog + first administrator into the freshly-onboarded tenant, and adding a
 *       user to an <em>existing</em> tenant via {@code POST /api/v1/admin/tenants/{id}/users}. Both
 *       touch {@code rbac}-owned {@code app_user}/{@code role} rows in a tenant other than the
 *       platform admin's own, which is the on-behalf-of shape by definition.
 * </ul>
 *
 * <p>{@code tenant.domain.TenantAdminService#create} deliberately does NOT go through this class —
 * the tenant-row + key + status-list half of onboarding is already {@code platform:admin}-exclusive
 * at the HTTP boundary with no other caller, so an in-service check there would be pure redundancy
 * (see that class's own Javadoc). A source scan under {@code src/main/java}, the same technique
 * {@code shared.SystemAccessCallerAllowlistTest} already established — a new caller means updating
 * this enumeration deliberately, not a silent addition.
 */
class OnBehalfOfCallerAllowlistTest {

  private static final Path SRC_MAIN = Path.of("").toAbsolutePath().resolve("src/main/java");
  private static final Pattern CALL_SITE = Pattern.compile("\\.runAsTenant\\(");

  /** Exactly the call sites spec FS-2.2 D4 covers — relative to {@code src/main/java}. */
  private static final Set<String> ALLOWED_FILES =
      Set.of(
          // Minting a TENANT API key for a tenant other than the caller's own.
          "sy/khatm/platform/rbac/web/AuthController.java",
          // KH-2.2b: onboarding catalog/first-admin + cross-tenant user create (spec FS-2.2 D6).
          "sy/khatm/platform/rbac/domain/TenantProvisioningService.java");

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
