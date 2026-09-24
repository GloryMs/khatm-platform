package sy.khatm.platform.issuerclient.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/** An issuer client as listed — never any form of the key, its secret, or its hash. */
@Schema(name = "IssuerClientResponse")
record IssuerClientResponse(
    UUID id,
    UUID tenantId,
    LocalizedText name,
    String keyPrefix,
    List<String> scopes,
    String status,
    List<UUID> allowedSchemaIds,
    UUID rotatedFrom,
    Instant retireAfter,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant createdAt) {}
