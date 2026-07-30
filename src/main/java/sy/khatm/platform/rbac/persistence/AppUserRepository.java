package sy.khatm.platform.rbac.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.rbac.domain.AppUser;

/**
 * Repository for {@link AppUser} entities.
 *
 * <p>Module-private — only the {@code rbac} module's domain services may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale.
 */
@Transactional(readOnly = true)
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

  Optional<AppUser> findByTenantIdAndUsername(UUID tenantId, String username);

  boolean existsByTenantId(UUID tenantId);

  /**
   * All users of a tenant, newest first (spec FS-2.2 D5 — {@code GET /api/v1/users}).
   *
   * @param tenantId the tenant
   * @return every user of that tenant, newest creation first
   */
  List<AppUser> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  /**
   * The {@code must_change_password} flag for one user, read live on every authenticated request by
   * {@code rbac.security.PasswordChangeEnforcementFilter} (KH-2.2b). Returns empty if the row no
   * longer exists — the filter then treats a vanished principal as <em>not</em> forced (it cannot
   * change a password for an account that is gone, and forcing would deadlock them out of the one
   * path that could resolve it); users are never deleted in this system, so this is defense, not an
   * expected path.
   *
   * @param id the authenticated user's id
   * @return the flag, or empty if no such user
   */
  @Query("SELECT u.mustChangePassword FROM AppUser u WHERE u.id = :id")
  Optional<Boolean> findMustChangePasswordById(@Param("id") UUID id);

  /**
   * Whether a user currently has an active (confirmed) TOTP enrollment — read live on every
   * authenticated request by {@code rbac.security.TotpEnrollmentEnforcementFilter} (spec FS-2.2
   * V1), the identical live-read rationale {@link #findMustChangePasswordById} documents: an
   * admin's {@code POST /users/{id}/totp/reset} must bite on the target's very next request even
   * mid-session, so this is never cached in the session principal. Returns empty if the row no
   * longer exists (same defense-only reasoning as the password-change flag).
   *
   * @param id the authenticated user's id
   * @return {@code true} if {@code totp_confirmed_at} is set, or empty if no such user
   */
  @Query("SELECT (u.totpConfirmedAt IS NOT NULL) FROM AppUser u WHERE u.id = :id")
  Optional<Boolean> findHasActiveTotpById(@Param("id") UUID id);

  /**
   * The number of {@code ACTIVE} users in a tenant who hold the {@code tenant:admin} scope (via any
   * assigned role), excluding one — the heart of the last-tenant-admin guard (spec FS-2.2 D5). A
   * zero here means the excluded user is the tenant's only remaining active administrator.
   *
   * <p>Native because it joins the {@code app_user} → {@code user_role} → {@code role} tables and
   * tests {@code 'tenant:admin' = ANY(role.scopes)} — the scope is what authorizes, not any one
   * role code, so the guard stays correct if a future catalog change grants {@code tenant:admin} to
   * a role other than {@code TENANT_ADMIN}.
   *
   * @param tenantId the tenant
   * @param excludeId the user the calling operation targets (not counted)
   * @return the count of other active administrators in that tenant
   */
  @Query(
      value =
          "SELECT COUNT(DISTINCT au.id) FROM app_user au "
              + "JOIN user_role ur ON ur.user_id = au.id "
              + "JOIN role r ON r.id = ur.role_id "
              + "WHERE au.tenant_id = :tenantId AND au.status = 'ACTIVE' "
              + "AND 'tenant:admin' = ANY(r.scopes) "
              + "AND au.id <> :excludeId",
      nativeQuery = true)
  long countActiveAdminsExcluding(
      @Param("tenantId") UUID tenantId, @Param("excludeId") UUID excludeId);
}
