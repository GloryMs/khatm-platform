package sy.khatm.platform.tenant.web;

import jakarta.validation.constraints.NotBlank;
import sy.khatm.platform.shared.LocalizedText;

/**
 * A bilingual display-name request fragment — both languages mandatory (CLAUDE.md work rule 2).
 * Bean Validation rejects a missing/blank {@code en} or {@code ar} with the uniform validation
 * envelope before it ever reaches a service.
 *
 * @param en English display text; required, non-blank
 * @param ar Arabic display text; required, non-blank
 */
record NameI18nRequest(@NotBlank String en, @NotBlank String ar) {

  LocalizedText toLocalizedText() {
    return new LocalizedText(en, ar);
  }
}
