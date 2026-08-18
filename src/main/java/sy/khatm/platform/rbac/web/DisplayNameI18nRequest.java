package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import sy.khatm.platform.shared.LocalizedText;

/**
 * A bilingual display-name request fragment — both languages mandatory (CLAUDE.md work rule 2).
 * Bean Validation rejects a missing/blank {@code en} or {@code ar} with the uniform validation
 * envelope before it ever reaches a service. Mirrors {@code tenant.web.NameI18nRequest} (this
 * module cannot depend on {@code tenant.web}).
 *
 * @param en English display text; required, non-blank
 * @param ar Arabic display text; required, non-blank
 */
record DisplayNameI18nRequest(
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String en,
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ar) {

  LocalizedText toLocalizedText() {
    return new LocalizedText(en, ar);
  }
}
