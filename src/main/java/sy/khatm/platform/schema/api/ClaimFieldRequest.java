package sy.khatm.platform.schema.api;

import io.swagger.v3.oas.annotations.media.Schema;
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
 * @param pattern optional regular expression a claim value must match at issuance time (KH-2.4,
 *     spec FS-2.4 item 3); {@code null} means no format constraint. Validated as a compilable regex
 *     at authoring time ({@link sy.khatm.platform.shared.error.ErrorCode#KH_SCH_0400} on an invalid
 *     pattern) and enforced against {@code IssueRequest.claims()} values at issuance ({@code
 *     CredentialService#issue}, same error code, "the standard schema-validation error envelope"
 *     per the session brief).
 */
public record ClaimFieldRequest(
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
    @NotNull Map<String, String> labelI18n,
    String pattern) {}
