package sy.khatm.platform.issuerclient.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Read model of an {@link IssuerClient} for the admin plane — everything except the key material.
 * There is deliberately no field here that could ever carry a secret or its hash.
 *
 * @param id the client id
 * @param tenantId the owning tenant
 * @param name bilingual display name
 * @param keyPrefix the lookup prefix as stored (the full key form is {@code
 *     khi_<keyPrefix>_<secret>}); safe to show and log
 * @param scopes granted scopes (v1: {@code issue})
 * @param status {@code ACTIVE}, {@code SUSPENDED}, {@code RETIRING}, or {@code REVOKED}
 * @param allowedSchemaIds schemas the client may issue against
 * @param rotatedFrom the client this one replaced, if any
 * @param retireAfter when a {@code RETIRING} client stops authenticating
 * @param expiresAt hard expiry, if configured
 * @param lastUsedAt last successful authentication (coarse — updated at most once a minute)
 * @param createdAt creation instant
 */
public record IssuerClientView(
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
