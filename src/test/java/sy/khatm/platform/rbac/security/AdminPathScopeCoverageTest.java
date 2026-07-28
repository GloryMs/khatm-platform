package sy.khatm.platform.rbac.security;

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
 * Spec FS-2.2 D1 — deny-by-default for the {@code /api/v1/admin/**} surface: every live mapping
 * annotation naming a path under {@code /api/v1/admin/} must fall under one of {@link
 * SecurityConfig}'s four granular-scope path families, never a fifth, uncovered one that would
 * silently fall through to the generic {@code anyRequest().authenticated()} catch-all (any
 * authenticated actor, no specific scope — exactly the "no declared scope" failure mode this test
 * exists to catch). A source scan under {@code src/main/java}, same technique as {@code
 * shared.SystemAccessCallerAllowlistTest} — deliberately no Spring context, stays fast.
 *
 * <p>A new controller mapped under {@code /api/v1/admin/newthing/**} with no matching {@link
 * SecurityConfig} rule fails this test immediately: naming a path is not the same as gating it, and
 * this is the mechanical proof that every admin path this session's D2 mapping named actually is
 * one of the four declared families, not an assumption from the mapping's shape alone.
 */
class AdminPathScopeCoverageTest {

  private static final Path SRC_MAIN = Path.of("").toAbsolutePath().resolve("src/main/java");

  /**
   * A quoted string literal that IS a path starting with {@code /api/v1/admin/} — anchored at the
   * opening quote and excluding whitespace, so a Javadoc sentence merely mentioning the substring
   * inside an unrelated quoted phrase (e.g. {@code "local"}) can never be matched.
   */
  private static final Pattern ADMIN_PATH_LITERAL = Pattern.compile("\"(/api/v1/admin/\\S*?)\"");

  /**
   * The four families {@link SecurityConfig} declares a specific scope for (spec D2) — kept as a
   * literal, independent list here rather than reading {@code SecurityConfig}'s own constants, so a
   * change to those constants' <em>values</em> (not just adding a new one) is itself visible as a
   * test diff, not silently inherited.
   */
  private static final List<String> COVERED_PREFIXES =
      List.of(
          "/api/v1/admin/tenants",
          "/api/v1/admin/consuming-parties",
          "/api/v1/admin/api-keys",
          "/api/v1/admin/signing-keys");

  @Test
  void everyLiveAdminPath_fallsUnderOneOfTheFourDeclaredScopeFamilies() throws IOException {
    List<String> uncovered = new ArrayList<>();
    try (Stream<Path> files = Files.walk(SRC_MAIN)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        Matcher matcher = ADMIN_PATH_LITERAL.matcher(content);
        while (matcher.find()) {
          String literal = matcher.group(1);
          String adminPath = literal.substring(literal.indexOf("/api/v1/admin/"));
          if (COVERED_PREFIXES.stream().noneMatch(adminPath::startsWith)) {
            uncovered.add(
                SRC_MAIN.relativize(file).toString().replace('\\', '/') + " -> " + adminPath);
          }
        }
      }
    }

    assertThat(uncovered)
        .as(
            "admin-plane path literals not covered by any of SecurityConfig's four granular-scope"
                + " families (tenants/consuming-parties/api-keys/signing-keys) — a new admin"
                + " endpoint needs its own SecurityConfig rule with an explicit ScopeRegistry"
                + " scope, never a silent fall-through to anyRequest().authenticated()")
        .isEmpty();
  }
}
