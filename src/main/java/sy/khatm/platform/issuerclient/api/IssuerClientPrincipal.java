package sy.khatm.platform.issuerclient.api;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated identity behind a valid {@code khi_} key (spec FS-2.7a D3).
 *
 * <p>The tenant here comes from the {@code issuer_client} row itself — never from a request body,
 * header, or path (SEC §5) — and is what {@code TenantContext} is set from for the whole request.
 *
 * @param clientId the {@code issuer_client.id}; recorded as the audit actor id and stamped on every
 *     credential the client issues
 * @param tenantId the client's own tenant
 * @param scopes the client's granted scopes (v1: {@code issue} only)
 */
public record IssuerClientPrincipal(UUID clientId, UUID tenantId, Set<String> scopes) {}
