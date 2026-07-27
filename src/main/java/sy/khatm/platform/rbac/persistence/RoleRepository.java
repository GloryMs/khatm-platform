package sy.khatm.platform.rbac.persistence;

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
}
