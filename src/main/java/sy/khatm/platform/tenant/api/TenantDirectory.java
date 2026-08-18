package sy.khatm.platform.tenant.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI for read-only tenant resolution (spec FS-2.1 D1/D7/D8).
 *
 * <p>The runtime cross-module surface of the {@code tenant} module — {@code rbac} depends on this
 * to resolve a principal's tenant and check its active status; {@code rbac.security
 * .TenantContextFilter} is the primary caller. Distinct from {@link TenantAdmin} (the admin-plane
 * management surface), the same split {@code consumer.api.ConsumingPartyRegistry}/{@code
 * ConsumingPartyAdmin} already established.
 */
public interface TenantDirectory {

  /**
   * Resolve a tenant by its internal id.
   *
   * @param tenantId the tenant's id
   * @return the tenant's reference, or {@link Optional#empty()} if no such tenant exists
   */
  Optional<TenantRef> findById(UUID tenantId);

  /**
   * Resolve a tenant by its slug — the public, per-tenant JWKS ({@code GET
   * /t/{tenantSlug}/.well-known/jwks.json}, spec D8) and status-list ({@code GET
   * /sl/{tenantSlug}/{listCode}}, spec FS-1.3 D2) endpoints both resolve their path's slug this
   * way.
   *
   * @param slug the tenant's slug
   * @return the tenant's reference, or {@link Optional#empty()} if no such tenant exists
   */
  Optional<TenantRef> findBySlug(String slug);

  /**
   * A tenant's full ancestor chain, nearest first (KH-2.6a, spec FS-2.5 §5/§7: "verify lineage —
   * full ancestor chain"). Display-only metadata (spec FS-2.5 §1 — administrative, never a security
   * or cryptographic signal); the caller ({@code credential.web.CredentialController #verify}) uses
   * this purely to render "issued by X, part of Y" and never to make a trust/authorization
   * decision. Bounded by the max hierarchy depth (three levels, §7) — never more than two entries.
   *
   * @param tenantId the tenant whose ancestors to resolve
   * @return every ancestor from the immediate parent up to the root, nearest first; empty if {@code
   *     tenantId} is itself a root or does not exist
   */
  List<TenantRef> ancestors(UUID tenantId);
}
