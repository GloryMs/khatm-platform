package sy.khatm.platform.schema.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * One claim field of a schema's {@code claims_def}, as submitted by the console's schema editor
 * (KH-1.1.1).
 *
 * @param name the claim's machine field name; must be unique within its schema's {@code claimsDef}
 * @param type the claim's value type; must be one of {@code text}, {@code number}, {@code date} —
 *     the set {@code SchemaAuthoringService} currently validates (rejected otherwise with {@link
 *     sy.khatm.platform.shared.error.ErrorCode#KH_SCH_0400})
 * @param labelI18n bilingual display label; must carry both non-blank {@code en} and {@code ar}
 *     keys (work rule 2, enforced here at the data layer — rejected otherwise)
 */
public record ClaimFieldRequest(
    @NotBlank String name, @NotBlank String type, @NotNull Map<String, String> labelI18n) {}
