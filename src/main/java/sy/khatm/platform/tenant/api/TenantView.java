package sy.khatm.platform.tenant.api;

import java.time.Instant;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Admin-plane view of a tenant (spec FS-2.1 D6, {@code /api/v1/admin/tenants}) — the shape a
 * platform-admin console screen renders.
 *
 * @param id internal UUID
 * @param slug the tenant's machine slug, unique platform-wide
 * @param nameI18n bilingual display name
 * @param type {@code GOVERNMENT}, {@code EDUCATION}, {@code PRIVATE}, or {@code OTHER}
 * @param deployMode {@code SAAS}, {@code ONPREM}, or {@code FEDERATED}
 * @param status {@code ACTIVE} or {@code SUSPENDED}
 * @param createdAt when the tenant was onboarded
 * @param parentSlug the immediate parent's slug, or {@code null} for a root (spec FS-2.5 §2,
 *     KH-2.6a) — additive
 * @param parentNameI18n the immediate parent's bilingual display name, or {@code null} for a root —
 *     additive
 */
public record TenantView(
    UUID id,
    String slug,
    LocalizedText nameI18n,
    String type,
    String deployMode,
    String status,
    Instant createdAt,
    String parentSlug,
    LocalizedText parentNameI18n) {}
