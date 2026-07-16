package sy.khatm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.VerifyReason;

/**
 * Spec FS-0.6a §4 — the reserved {@code MessageBundleParityTest} (CONVENTIONS.md §3): the EN and AR
 * bundles must carry exactly the same key set with no blank values, and every {@link
 * ErrorCode}/{@link VerifyReason} message key must have an entry. No Spring context — pure file
 * I/O, same style as {@code MigrationImmutabilityTest}.
 */
class MessageBundleParityTest {

  @Test
  void bundles_haveIdenticalKeySets() throws IOException {
    Properties en = loadBundle("i18n/messages_en.properties");
    Properties ar = loadBundle("i18n/messages_ar.properties");

    assertThat(en.stringPropertyNames())
        .as("en has keys ar is missing")
        .containsExactlyInAnyOrderElementsOf(ar.stringPropertyNames());
  }

  @Test
  void bundles_haveNoBlankValues() throws IOException {
    Properties en = loadBundle("i18n/messages_en.properties");
    Properties ar = loadBundle("i18n/messages_ar.properties");

    for (String key : en.stringPropertyNames()) {
      assertThat(en.getProperty(key)).as("messages_en.properties[%s]", key).isNotBlank();
    }
    for (String key : ar.stringPropertyNames()) {
      assertThat(ar.getProperty(key)).as("messages_ar.properties[%s]", key).isNotBlank();
    }
  }

  @Test
  void everyErrorCode_hasAMessageKeyInBothBundles() throws IOException {
    Properties en = loadBundle("i18n/messages_en.properties");
    Properties ar = loadBundle("i18n/messages_ar.properties");

    for (ErrorCode code : ErrorCode.values()) {
      assertThat(en.containsKey(code.messageKey()))
          .as("messages_en.properties missing key '%s' for %s", code.messageKey(), code)
          .isTrue();
      assertThat(ar.containsKey(code.messageKey()))
          .as("messages_ar.properties missing key '%s' for %s", code.messageKey(), code)
          .isTrue();
    }
  }

  @Test
  void everyVerifyReason_hasAMessageKeyInBothBundles() throws IOException {
    Properties en = loadBundle("i18n/messages_en.properties");
    Properties ar = loadBundle("i18n/messages_ar.properties");

    for (VerifyReason reason : VerifyReason.values()) {
      assertThat(en.containsKey(reason.messageKey()))
          .as("messages_en.properties missing key '%s' for %s", reason.messageKey(), reason)
          .isTrue();
      assertThat(ar.containsKey(reason.messageKey()))
          .as("messages_ar.properties missing key '%s' for %s", reason.messageKey(), reason)
          .isTrue();
    }
  }

  @Test
  void arabicBundle_valuesContainActualArabicCharacters() throws IOException {
    Properties ar = loadBundle("i18n/messages_ar.properties");
    Set<String> keys = ar.stringPropertyNames();
    assertThat(keys).isNotEmpty();
    for (String key : keys) {
      String value = ar.getProperty(key);
      boolean hasArabicChar = value.chars().anyMatch(MessageBundleParityTest::isArabicCodePoint);
      assertThat(hasArabicChar)
          .as(
              "messages_ar.properties[%s] = '%s' has no Arabic characters — encoding likely broken",
              key, value)
          .isTrue();
    }
  }

  private static boolean isArabicCodePoint(int codePoint) {
    Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
    return Character.UnicodeBlock.ARABIC.equals(block);
  }

  /**
   * Load a {@code .properties} bundle explicitly as UTF-8 — {@link Properties#load(InputStream)}
   * defaults to ISO-8859-1 and would silently corrupt the Arabic bundle in this test too (same
   * reasoning as {@code LocaleConfig}'s explicit encoding, spec FS-0.6a §4).
   */
  private static Properties loadBundle(String classpathResource) throws IOException {
    Properties props = new Properties();
    try (InputStream in =
            Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathResource);
        InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      props.load(reader);
    }
    return props;
  }
}
