package sy.khatm.platform.tenant.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.tenant.domain.Tenant;

/**
 * Repository for {@link Tenant} entities.
 *
 * <p>Module-private — only code within the {@code tenant} module may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale. ({@code tenant}
 * itself is excluded from RLS, but {@code TenantAdminService#create} is deliberately
 * non-{@code @Transactional} too, so this repository's bare calls need the same safety net.)
 */
@Transactional(readOnly = true)
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

  Optional<Tenant> findBySlug(String slug);

  List<Tenant> findAllByOrderByCreatedAtDesc();

  /**
   * A tenant's direct children (spec FS-2.5 §7 — {@code org:admin} operations and the {@code
   * setParent} depth/cycle guards both only ever need one level at a time, never a recursive
   * fetch).
   *
   * @param parentTenantId the parent tenant's id
   * @return every tenant whose {@code parent_tenant_id} is this one
   */
  List<Tenant> findAllByParentTenantId(UUID parentTenantId);

  /**
   * Whether a tenant has at least one direct child currently {@code ACTIVE} (KH-2.6a, spec FS-2.5
   * §2) — backs the no-cascade guard on {@code TenantAdminService#flipStatus}'s suspend path.
   *
   * @param parentTenantId the tenant to check
   * @param status the child status to match, e.g. {@code "ACTIVE"}
   * @return {@code true} if at least one such child exists
   */
  boolean existsByParentTenantIdAndStatus(UUID parentTenantId, String status);

  /**
   * Update {@code key_provider} for a single tenant (spec FS-2.3 D5/D6, veto V3) — a bulk {@code
   * UPDATE} rather than a load-mutate-save round trip since {@code tenant
   * .domain.TenantKeyProviderSyncHandler} runs on a worker thread reacting to {@link
   * sy.khatm.platform.key.events.KeyRotated}, with no other reason to load the full entity.
   *
   * @param tenantId the tenant to update
   * @param provider the tenant's new current provider
   * @return the number of rows updated (0 or 1)
   */
  @Modifying
  @Transactional
  @Query("UPDATE Tenant t SET t.keyProvider = :provider WHERE t.id = :tenantId")
  int updateKeyProvider(@Param("tenantId") UUID tenantId, @Param("provider") String provider);
}
