package sy.khatm.platform.issuerclient.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import sy.khatm.platform.shared.LocalizedText;

/** Bilingual display name — both languages are mandatory (CLAUDE.md work rule 2). */
record NameI18nRequest(
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String en,
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ar) {

  LocalizedText toLocalizedText() {
    return new LocalizedText(en, ar);
  }
}
