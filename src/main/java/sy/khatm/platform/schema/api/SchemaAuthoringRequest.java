package sy.khatm.platform.schema.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * The authoring fields shared by {@code PUT /api/v1/schemas/{id}} (rewrite a {@code DRAFT} in
 * place) and {@code POST /api/v1/schemas/{id}/versions} (create a new {@code DRAFT} version of a
 * {@code PUBLISHED} schema) — KH-1.1.1. Neither endpoint accepts {@code code}: the update path
 * cannot change it, and the version path inherits it from the source schema named in the path.
 *
 * <p>Validated identically to {@link SchemaCreateRequest}'s own fields — see that record's Javadoc
 * for the per-field rules.
 */
public record SchemaAuthoringRequest(
    @NotNull Map<String, String> nameI18n,
    @NotEmpty @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<@Valid ClaimFieldRequest> claimsDef,
    List<String> sdFields,
    Integer defaultMaxUses,
    String defaultValidity,
    Boolean requiresAttestation) {}
