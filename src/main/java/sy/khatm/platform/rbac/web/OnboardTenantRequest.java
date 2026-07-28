package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * {@code POST /api/v1/admin/tenants} request body (spec FS-2.2 D6) — onboards a tenant and,
 * optionally, its first administrator in one call. Carries the same tenant fields the relocated
 * {@code tenant}-side create used to, plus the optional {@code initialAdmin}.
 *
 * @param slug lowercase machine slug; format validated by the service ({@code KH-TNT-0400})
 * @param nameI18n bilingual tenant display name; both languages required
 * @param type {@code GOVERNMENT}, {@code EDUCATION}, {@code PRIVATE}, or {@code OTHER}
 * @param deployMode {@code SAAS}, {@code ONPREM}, or {@code FEDERATED}; omit for the default
 * @param initialAdmin optional first administrator (a {@code TENANT_ADMIN}); omit for no initial
 *     admin
 */
@Schema(description = "Onboard a tenant, optionally with its first administrator")
record OnboardTenantRequest(
    @NotBlank String slug,
    @NotNull @Valid DisplayNameI18nRequest nameI18n,
    @NotBlank @Pattern(regexp = "GOVERNMENT|EDUCATION|PRIVATE|OTHER") String type,
    @Pattern(regexp = "SAAS|ONPREM|FEDERATED") String deployMode,
    @Valid InitialAdminRequest initialAdmin) {

  /**
   * The tenant's first administrator.
   *
   * @param username a valid username slug; format validated by the service ({@code KH-USR-0400})
   * @param displayNameI18n bilingual display name; both languages required
   */
  @Schema(description = "The tenant's first administrator (optional)")
  record InitialAdminRequest(
      @NotBlank String username, @NotNull @Valid DisplayNameI18nRequest displayNameI18n) {}
}
