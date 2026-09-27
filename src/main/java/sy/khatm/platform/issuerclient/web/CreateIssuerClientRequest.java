package sy.khatm.platform.issuerclient.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request to create an issuer client.
 *
 * @param name bilingual display name
 * @param allowedSchemaIds schemas the client may issue against (deny-by-default: none = nothing)
 * @param expiresAt optional hard expiry of the key
 */
@Schema(name = "CreateIssuerClientRequest")
record CreateIssuerClientRequest(
    @NotNull @Valid @Schema(requiredMode = Schema.RequiredMode.REQUIRED) NameI18nRequest name,
    List<UUID> allowedSchemaIds,
    Instant expiresAt) {}
