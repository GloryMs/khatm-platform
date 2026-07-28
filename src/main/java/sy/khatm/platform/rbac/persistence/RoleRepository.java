package sy.khatm.platform.rbac.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.rbac.domain.Role;

/**
 * Repository for {@link Role} entities, plus the {@code user_role} join-table operations a role
 * assignment needs (there is no JPA entity for {@code user_role} itself — a bare composite-key join
 * table with no columns of its own carries nothing worth mapping).
 *
 * <p>Module-private — only the {@code rbac} module's domain services may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale.
 */
@Transactional(readOnly = true)
public interface RoleRepository extends JpaRepository<Role, UUID> {

  Optional<Role> findByTenantIdAndCode(UUID tenantId, String code);

  /**
   * The distinct union of every {@code scopes} entry across all roles assigned to a user, via the
   * {@code user_role} join table.
   *
   * @param userId the user to resolve scopes for
   * @return the user's aggregate scope set (each scope once, order not significant)
   */
  @Query(
      value =
          "SELECT DISTINCT s FROM role r "
              + "JOIN user_role ur ON ur.role_id = r.id, "
              + "LATERAL unnest(r.scopes) AS s "
              + "WHERE ur.user_id = :userId",
      nativeQuery = true)
  List<String> findScopesByUserId(@Param("userId") UUID userId);

  /**
   * The codes of every role assigned to a user (spec FS-2.2 D5 — the {@code roles[]} field of a
   * {@code UserSummary}). Distinct is harmless: a user can hold a given catalog role at most once
   * per the {@code user_role} composite primary key.
   *
   * @param userId the user
   * @return the user's assigned role codes
   */
  @Query(
      value =
          "SELECT r.code FROM role r JOIN user_role ur ON ur.role_id = r.id WHERE ur.user_id ="
              + " :userId",
      nativeQuery = true)
  List<String> findRoleCodesByUserId(@Param("userId") UUID userId);

  /**
   * Assign a role to a user via the {@code user_role} join table.
   *
   * <p>{@code tenant_id} (KH-2.1, spec FS-2.1 D2 — backfilled onto this join table by {@code
   * V7__rls_policies.sql}) is derived from the user's own row rather than passed in, so this
   * method's signature and every caller stay unchanged.
   *
   * @param userId the user
   * @param roleId the role to assign
   */
  @Modifying
  @Transactional
  @Query(
      value =
          "INSERT INTO user_role (user_id, role_id, tenant_id)"
              + " SELECT :userId, :roleId, tenant_id FROM app_user WHERE id = :userId",
      nativeQuery = true)
  void assignRole(@Param("userId") UUID userId, @Param("roleId") UUID roleId);

  /**
   * Resolve the tenant's catalog roles matching a set of codes (spec FS-2.2 D5 — a user's roles are
   * chosen from the fixed three-role seeded catalog). Used to validate that every requested code is
   * a real catalog role in this tenant and to map codes to role ids for assignment.
   *
   * @param tenantId the tenant whose catalog to resolve against
   * @param codes the requested role codes
   * @return the matching catalog roles (fewer than {@code codes} if any code is unknown)
   */
  List<Role> findByTenantIdAndCodeIn(UUID tenantId, Collection<String> codes);

  /**
   * Remove every role assignment for a user (spec FS-2.2 D5 — role-set replacement is
   * delete-all-then-reinsert). Backed by the {@code DELETE} grant {@code V11__user_password_change
   * _and_role_grants.sql} added to {@code user_role} for exactly this operation (the same
   * documented exception {@code V7} already made for {@code consuming_party_schema}).
   *
   * @param userId the user whose role set is being replaced
   */
  @Modifying
  @Transactional
  @Query(value = "DELETE FROM user_role WHERE user_id = :userId", nativeQuery = true)
  void deleteAllByUserId(@Param("userId") UUID userId);
}
