package sy.khatm.platform.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One field-level validation failure inside an {@link ErrorEnvelope#details()} array (CLAUDE.md
 * work rule 3, spec FS-0.6a D4).
 *
 * @param field the request field that failed validation, e.g. {@code holderRef}
 * @param messageKey the {@code MessageSource} key the constraint resolved from, e.g. {@code
 *     validation.NotBlank}
 * @param message {@code messageKey} resolved to the request's locale
 */
@Schema(name = "ErrorDetail", description = "One field-level validation failure")
public record ErrorDetail(String field, String messageKey, String message) {}
