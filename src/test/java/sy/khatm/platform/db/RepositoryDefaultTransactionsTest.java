package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * KH-2.1 Part B (spec FS-2.1 D4) — every {@code JpaRepository} interface must carry a type-level
 * {@code @Transactional(readOnly = true)}, and every {@code @Modifying} method must carry an
 * explicit, bare {@code @Transactional} override.
 *
 * <p>Without the type-level default, a derived-query method called with no ambient transaction (a
 * handful of production call sites are deliberately non-{@code @Transactional} for unrelated
 * reasons, e.g. {@code CredentialService#enforceSchemaAllowlist}'s independent-commit requirement,
 * and plenty of test methods call a repository directly to verify state) runs via {@code
 * SharedEntityManagerCreator}'s non-transactional path: {@code
 * shared.TenantContextTransactionExecutionListener} never fires, {@code app.tenant_id} is never
 * set, and RLS closed-fails to zero rows regardless of the real data — this silently turned {@code
 * enforceSchemaAllowlist}'s "can't resolve this schema, don't block" fallback into "can never
 * resolve any schema, always allow" (caught by {@code rbac.ConsumeApiKeyGateTest}). The type-level
 * annotation is lowest priority in Spring's {@code AnnotationTransactionAttributeSource} lookup
 * order, so it is a no-op wherever a method already carries its own more specific annotation (e.g.
 * {@code SimpleJpaRepository}'s own {@code save}/{@code delete}) or is called from inside a real
 * service transaction — it only changes behavior for the previously-bare, previously-broken call
 * sites this class exists to close off.
 *
 * <p>{@code @Modifying} methods need their own explicit, non-{@code readOnly}
 * {@code @Transactional} precisely because the inherited type-level default is {@code readOnly =
 * true} — a bare {@code @Modifying} query run read-only would fail (or silently not flush), so
 * every one of these needs its own override, and this test pins that down as a structural invariant
 * rather than something a future edit could silently regress.
 *
 * <p>A source scan under {@code src/main/java}, same technique as {@code
 * TenantContextConstantAllowlistTest}/{@code SystemAccessCallerAllowlistTest} — deliberately no
 * Spring context, stays fast.
 */
class RepositoryDefaultTransactionsTest {

  private static final Path SRC_MAIN = Path.of("").toAbsolutePath().resolve("src/main/java");

  private static final Pattern REPOSITORY_INTERFACE =
      Pattern.compile("(?m)^(?:public\\s+)?interface\\s+(\\w+)\\s+extends\\s+JpaRepository\\b");

  private static final Pattern TYPE_LEVEL_READ_ONLY_TRANSACTIONAL =
      Pattern.compile(
          "@Transactional\\(readOnly\\s*=\\s*true\\)\\s*\\n(?:public\\s+)?interface\\s+\\w+\\s+"
              + "extends\\s+JpaRepository\\b");

  private static final Pattern MODIFYING_METHOD =
      Pattern.compile("@Modifying(?:\\([^)]*\\))?\\s*\\n\\s*(@\\w[^\\n]*)");

  @Test
  void everyJpaRepositoryInterface_carriesTypeLevelReadOnlyTransactional() throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(SRC_MAIN)) {
      for (Path file : files.filter(p -> p.toString().endsWith("Repository.java")).toList()) {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (!REPOSITORY_INTERFACE.matcher(content).find()) {
          continue;
        }
        if (!TYPE_LEVEL_READ_ONLY_TRANSACTIONAL.matcher(content).find()) {
          violations.add(SRC_MAIN.relativize(file).toString().replace('\\', '/'));
        }
      }
    }

    assertThat(violations)
        .as(
            "every JpaRepository interface must carry '@Transactional(readOnly = true)'"
                + " immediately above its interface declaration (spec FS-2.1 D4)")
        .isEmpty();
  }

  @Test
  void everyModifyingMethod_carriesAnExplicitBareTransactionalOverride() throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(SRC_MAIN)) {
      for (Path file : files.filter(p -> p.toString().endsWith("Repository.java")).toList()) {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        Matcher matcher = MODIFYING_METHOD.matcher(content);
        while (matcher.find()) {
          String nextAnnotation = matcher.group(1).trim();
          if (!nextAnnotation.equals("@Transactional")) {
            violations.add(
                SRC_MAIN.relativize(file).toString().replace('\\', '/')
                    + " (found '"
                    + nextAnnotation
                    + "' instead of a bare '@Transactional')");
          }
        }
      }
    }

    assertThat(violations)
        .as(
            "every @Modifying repository method must be immediately followed by a bare"
                + " '@Transactional' (no readOnly) overriding the type-level default (spec FS-2.1"
                + " D4)")
        .isEmpty();
  }
}
