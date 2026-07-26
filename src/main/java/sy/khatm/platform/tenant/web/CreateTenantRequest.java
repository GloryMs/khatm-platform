package sy.khatm.platform.tenant.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request to onboard a tenant (spec FS-2.1 D6, {@code POST /api/v1/admin/tenants}) — creation is
 * full onboarding: the tenant row, its first {@code ACTIVE} signing key, and its default status
 * list are all provisioned before this call returns.
 *
 * @param slug lowercase machine slug, {@code ^[a-z0-9][a-z0-9-_]{1,62}$} — validated by the service
 *     (a bad format is {@code KH-TNT-0400}, not a generic Bean-Validation failure)
 * @param nameI18n bilingual display name; both {@code en} and {@code ar} required
 * @param type {@code GOVERNMENT}, {@code EDUCATION}, {@code PRIVATE}, or {@code OTHER}
 * @param deployMode {@code SAAS}, {@code ONPREM}, or {@code FEDERATED}; omit for the database
 *     column's own default ({@code SAAS})
 */
record CreateTenantRequest(
    String slug,
    @NotNull @Valid NameI18nRequest nameI18n,
    @NotBlank @Pattern(regexp = "GOVERNMENT|EDUCATION|PRIVATE|OTHER") String type,
    @Pattern(regexp = "SAAS|ONPREM|FEDERATED") String deployMode) {}
