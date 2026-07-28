package sy.khatm.platform.rbac.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * {@code POST /api/v1/admin/tenants} response body (spec FS-2.2 D6) — the onboarded tenant's view
 * plus, when an initial admin was requested and freshly created, its one-time temporary password.
 *
 * <p>The tenant fields mirror {@code tenant.api.TenantView} (flattened, not wrapped, so existing
 * callers reading {@code id}/{@code status}/etc. at the response root are unaffected — the {@code
 * initialAdmin} field is purely additive). When {@code initialAdmin} is non-null but its {@code
 * temporaryPassword} is null, a prior partial onboarding had already created that admin (resume,
 * spec V3) — the admin exists exactly once, and the caller must reset it for a fresh temporary
 * credential.
 */
@Schema(
    description =
        "An onboarded tenant, optionally with its first administrator's one-time password")
record OnboardTenantResponse(
    UUID id,
    String slug,
    LocalizedText nameI18n,
    String type,
    String deployMode,
    String status,
    Instant createdAt,
    InitialAdminResponse initialAdmin) {

  /**
   * The first administrator's username and, when freshly created, its one-time temporary password.
   */
  @Schema(description = "The first administrator (null when none was requested)")
  record InitialAdminResponse(String username, String temporaryPassword) {}
}
