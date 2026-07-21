package sy.khatm.platform.schema.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * Request to create a new {@code DRAFT} credential schema, version {@code 1} (KH-1.1.1, {@code POST
 * /api/v1/schemas}).
 *
 * @param code the schema's machine code; must not already be registered for this tenant at version
 *     1
 * @param nameI18n bilingual display name; must carry both non-blank {@code en} and {@code ar} keys
 * @param claimsDef the schema's claim fields; must not be empty, field names must be unique, and
 *     each field's {@code type}/{@code labelI18n} must pass {@link ClaimFieldRequest}'s own rules
 * @param sdFields names from {@code claimsDef} the holder is permitted to withhold at presentation
 *     time (spec FS-0.4 D2); every entry must name a field actually present in {@code claimsDef};
 *     {@code null} or empty means every claim is mandatory to disclose
 * @param defaultMaxUses default max consumption count for credentials issued against this schema;
 *     {@code null} defaults to {@code 1}; must be {@code >= 1} if given
 * @param defaultValidity default validity window as an ISO-8601 duration string (e.g. {@code
 *     "P90D"}); {@code null} means no configured default
 */
public record SchemaCreateRequest(
    @NotBlank String code,
    @NotNull Map<String, String> nameI18n,
    @NotEmpty List<@Valid ClaimFieldRequest> claimsDef,
    List<String> sdFields,
    Integer defaultMaxUses,
    String defaultValidity) {}
