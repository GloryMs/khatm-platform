package sy.khatm.platform.shared;

import java.util.Objects;

/**
 * A bilingual (English/Arabic) display string, mirroring the {@code name_i18n} / {@code label_i18n}
 * JSONB convention used across the platform (CLAUDE.md work rule 2).
 *
 * <p>Both languages are mandatory for tenant-facing rows: every table that carries a {@code
 * name_i18n} column enforces {@code CHECK (name_i18n ? 'en' AND name_i18n ? 'ar')} at the database
 * level (spec FS-0.2 §2 D4). Persisted via {@link LocalizedTextConverter}.
 *
 * @param en English text; must not be {@code null}
 * @param ar Arabic text; must not be {@code null}
 */
public record LocalizedText(String en, String ar) {

  public LocalizedText {
    Objects.requireNonNull(en, "en must not be null");
    Objects.requireNonNull(ar, "ar must not be null");
  }

  /**
   * Resolve the display string for a request locale, falling back to English.
   *
   * @param locale {@code "ar"} or {@code "en"}; any other value falls back to English
   * @return the localized text for {@code locale}
   */
  public String resolve(String locale) {
    return "ar".equals(locale) ? ar : en;
  }
}
