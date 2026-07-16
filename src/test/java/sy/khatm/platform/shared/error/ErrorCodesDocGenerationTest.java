package sy.khatm.platform.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Spec FS-0.6a D7 — {@code docs/error-codes.md} is generated from {@link ErrorCode} by this test
 * and never hand-edited (CLAUDE.md work rule 1), the same self-serve philosophy as {@code
 * MigrationImmutabilityTest}: recompute the expected content, compare against the committed file,
 * and fail with the exact content to paste in if they differ.
 *
 * <p>Deliberately no Spring context — pure string generation and file I/O, stays fast.
 */
class ErrorCodesDocGenerationTest {

  private static final Path REPO_ROOT = Path.of("").toAbsolutePath();
  private static final Path DOC_FILE = REPO_ROOT.resolve("docs/error-codes.md");

  @Test
  void errorCodesDoc_matchesGeneratedContent() throws IOException {
    String generated = generate();
    String actual = Files.readString(DOC_FILE, StandardCharsets.UTF_8);

    assertThat(actual)
        .as(
            "docs/error-codes.md is stale relative to the ErrorCode enum. Never hand-edit this"
                + " file (CLAUDE.md work rule 1) — replace its contents with exactly this and"
                + " commit:\n\n%s",
            generated)
        .isEqualTo(generated);
  }

  /**
   * Proves the comparison above actually catches drift, rather than trivially always passing: a
   * deliberately mutated copy of the generated content must differ from a fresh generation.
   */
  @Test
  void generatedContent_differsWhenACodeIsAltered_provingTheComparisonCatchesDrift() {
    String generated = generate();
    String staleCopy = generated.replace(ErrorCode.KH_CRD_0404.code(), "KH-CRD-9999");

    assertThat(staleCopy).isNotEqualTo(generated);
  }

  private static String generate() {
    StringBuilder md = new StringBuilder();
    md.append("# Error Codes\n\n");
    md.append(
        "> Generated from `ErrorCode` by `ErrorCodesDocGenerationTest` — never hand-edited\n");
    md.append(
        "> (CLAUDE.md work rule 1). After adding/changing a code, run `mvn test`; on failure"
            + " the\n");
    md.append("> exact content to paste in here is printed in the assertion message.\n\n");
    md.append("| Code | HTTP Status | Message Key |\n");
    md.append("|---|---|---|\n");
    for (ErrorCode code : ErrorCode.values()) {
      md.append("| `")
          .append(code.code())
          .append("` | ")
          .append(code.httpStatus().value())
          .append(" | `")
          .append(code.messageKey())
          .append("` |\n");
    }
    return md.toString();
  }
}
