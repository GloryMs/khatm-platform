package sy.khatm.platform.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Spec FS-0.6b DoD #7 — no module writes {@code audit_log} directly; every event goes through
 * {@link AuditService#record}. This is the "architectural test" the spec asks for (ArchUnit isn't
 * an approved new dependency this session — see the session's pom constraints — so this achieves
 * the same "the build catches it, not a manual review" guarantee the same way {@code
 * MigrationImmutabilityTest} and {@code ErrorCodesDocGenerationTest} already do in this codebase:
 * pure source-text scanning, no framework).
 *
 * <p>Scans every {@code .java} file under {@code src/main/java} for the literal SQL fragment {@code
 * INSERT INTO audit_log}, case-insensitively, and fails if one appears anywhere outside {@code
 * shared/audit/} (this package's own {@code AuditLogRepository}/{@code AuditLogEntry}, which are
 * the only code allowed to touch the table directly).
 */
class NoDirectAuditLogInsertTest {

  private static final Path SRC_MAIN = Path.of("").toAbsolutePath().resolve("src/main/java");
  private static final Pattern INSERT_PATTERN =
      Pattern.compile("insert\\s+into\\s+audit_log", Pattern.CASE_INSENSITIVE);
  private static final String ALLOWED_PACKAGE_FRAGMENT =
      ("sy"
              + java.io.File.separator
              + "khatm"
              + java.io.File.separator
              + "platform"
              + java.io.File.separator
              + "shared"
              + java.io.File.separator
              + "audit")
          .replace(java.io.File.separatorChar, '/');

  @Test
  void noJavaFileOutsideSharedAudit_containsADirectAuditLogInsert() throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(SRC_MAIN)) {
      files
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(
              file -> {
                String normalized = file.toString().replace('\\', '/');
                if (normalized.contains(ALLOWED_PACKAGE_FRAGMENT)) {
                  return;
                }
                String content;
                try {
                  content = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
                if (INSERT_PATTERN.matcher(content).find()) {
                  violations.add(file.toString());
                }
              });
    }

    assertThat(violations)
        .as(
            "these files write audit_log directly instead of going through"
                + " shared.audit.AuditService#record (spec FS-0.6b D8/DoD #7)")
        .isEmpty();
  }
}
