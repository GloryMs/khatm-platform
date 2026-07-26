package sy.khatm.platform.rbac.web;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;

/**
 * {@code POST /api/v1/admin/api-keys} request body (spec FS-0.6b DoD #5).
 *
 * @param ownerType who this key acts on behalf of
 * @param ownerId the owning consuming party's id ({@code CONSUMING_PARTY} only); must be {@code
 *     null} for {@code TENANT}
 * @param scopes the scopes to grant this key
 * @param tenantId the tenant this key belongs to (spec FS-2.1); omit to default to the caller's own
 *     tenant — a platform admin provisioning a newly onboarded tenant's first key names it
 *     explicitly, since {@code TenantContext.current()} always resolves to the caller's own tenant
 */
record CreateApiKeyRequest(
    @NotNull ApiKeyOwnerType ownerType,
    UUID ownerId,
    @NotEmpty Set<String> scopes,
    UUID tenantId) {}
