package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * KH-0.2.2 — append-only migration discipline, local layer.
 *
 * <p>Recomputes the SHA-256 of every file under {@code src/main/resources/db/migration/} and
 * compares it against the committed {@code db/migration-checksums.lock}. Fails the build if:
 *
 * <ul>
 *   <li>a locked migration's checksum no longer matches (the file was edited after being applied —
 *       forbidden by CLAUDE.md "Database rules": "Never edit an applied migration — append a new
 *       one");
 *   <li>a locked migration's file was deleted;
 *   <li>a migration file exists with no corresponding line in the lock file (a new migration was
 *       added without registering its checksum).
 * </ul>
 *
 * <p>Deliberately does not use {@code @SpringBootTest} or Testcontainers — this is pure file I/O
 * and must stay fast (CONVENTIONS.md §7: "no Spring context where avoidable"), so it can later be
 * run as a cheap, early step in KH-0.3.1's CI pipeline before any DB-backed tests.
 */
class MigrationImmutabilityTest {

  private static final Path REPO_ROOT = Path.of("").toAbsolutePath();
  private static final Path LOCK_FILE = REPO_ROOT.resolve("db/migration-checksums.lock");
  private static final Path MIGRATIONS_DIR = REPO_ROOT.resolve("src/main/resources/db/migration");

  @Test
  void migrationFiles_matchLockFile_noEditsNoDeletionsNoUnregisteredFiles() throws IOException {
    Map<String, String> locked = readLockFile(LOCK_FILE);
    Map<String, Path> actual = listMigrationFiles(MIGRATIONS_DIR);

    List<String> violations = new ArrayList<>();

    for (Map.Entry<String, String> entry : locked.entrySet()) {
      String filename = entry.getKey();
      String expectedHash = entry.getValue();
      Path file = actual.get(filename);
      if (file == null) {
        violations.add(
            "DELETED: migration '"
                + filename
                + "' is in db/migration-checksums.lock (checksum "
                + expectedHash
                + ") but the file no longer exists under src/main/resources/db/migration/. "
                + "Deleting an applied migration is forbidden — see docs/CONVENTIONS.md "
                + "'Migrations are append-only'.");
        continue;
      }
      String actualHash = sha256(file);
      if (!actualHash.equals(expectedHash)) {
        violations.add(
            "MODIFIED: migration '"
                + filename
                + "' was edited after being applied. Locked checksum "
                + expectedHash
                + ", actual checksum "
                + actualHash
                + ". Migrations are append-only (CLAUDE.md 'Database rules': \"Never edit an "
                + "applied migration — append a new one\") — revert this edit and add a NEW "
                + "migration file instead.");
      }
    }

    for (String filename : actual.keySet()) {
      if (!locked.containsKey(filename)) {
        String actualHash = sha256(actual.get(filename));
        violations.add(
            "UNREGISTERED: migration '"
                + filename
                + "' has no entry in db/migration-checksums.lock. Adding a NEW migration "
                + "requires adding its checksum line. Add this line to "
                + "db/migration-checksums.lock, then re-run the build:\n\t"
                + filename
                + "\t"
                + actualHash);
      }
    }

    assertThat(violations).as("migration lock violations").isEmpty();
  }

  private static Map<String, String> readLockFile(Path lockFile) throws IOException {
    Map<String, String> locked = new LinkedHashMap<>();
    for (String line : Files.readAllLines(lockFile, StandardCharsets.UTF_8)) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      String[] parts = trimmed.split("\t", 2);
      locked.put(parts[0], parts[1]);
    }
    return locked;
  }

  private static Map<String, Path> listMigrationFiles(Path migrationsDir) throws IOException {
    Map<String, Path> files = new LinkedHashMap<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(migrationsDir, "*.sql")) {
      for (Path path : stream) {
        files.put(path.getFileName().toString(), path);
      }
    }
    return files;
  }

  private static String sha256(Path file) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm", e);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read migration file: " + file, e);
    }
  }
}
