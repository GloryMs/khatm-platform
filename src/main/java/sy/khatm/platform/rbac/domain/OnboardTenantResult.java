package sy.khatm.platform.rbac.domain;

import sy.khatm.platform.tenant.api.TenantView;

/**
 * The outcome of onboarding a tenant with an optional first administrator (spec FS-2.2 D6).
 *
 * <p>{@code initialAdminUsername} is {@code null} when no initial admin was requested. When it is
 * non-null, {@code initialAdminTemporaryPassword} is the one-time password <em>unless</em> a prior
 * partial onboarding had already created that admin (resume, spec V3) — in which case it is {@code
 * null} because the platform stores only the password's hash and can never re-display it; the
 * caller must reset it to get a fresh temporary credential. Either way the admin exists exactly
 * once.
 *
 * @param tenant the onboarded (or resumed) tenant
 * @param initialAdminUsername the first admin's username, or {@code null} if none was requested
 * @param initialAdminTemporaryPassword the first admin's one-time password, or {@code null} if no
 *     admin was requested or the admin already existed
 */
public record OnboardTenantResult(
    TenantView tenant, String initialAdminUsername, String initialAdminTemporaryPassword) {}
