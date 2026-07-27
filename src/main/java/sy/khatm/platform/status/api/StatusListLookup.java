package sy.khatm.platform.status.api;

import java.util.Optional;
import java.util.UUID;

/**
 * SPI for read-only status-list resolution (spec FS-1.3 D6/D7).
 *
 * <p>One of the {@code status} module's cross-module surfaces, alongside {@link
 * StatusListAllocator} and {@link StatusListRevoker}. {@code credential.domain.CredentialService}
 * calls this from {@code verify} to fill the additive {@code statusListChecked}/{@code
 * statusListVersion}/{@code statusListUri} response fields, and {@code
 * credential.domain.ClaimRedemptionService} calls it to resolve the real {@code statusListUri} in
 * {@code ClaimRedeemResponse} (replacing the pre-KH-1.3 placeholder). No row lock — this is a plain
 * read, safe to call on every {@code /verify} request (spec D6: "رخيص محلياً").
 */
public interface StatusListLookup {

  /**
   * Resolve a status list's current version and public URL.
   *
   * @param statusListId the status list to resolve
   * @return the {@link StatusListRef}, or {@link Optional#empty()} only if {@code statusListId}
   *     does not reference an existing row (should not happen in practice — every caller obtains it
   *     from a {@code credential.status_list_id} foreign key)
   */
  Optional<StatusListRef> findRef(UUID statusListId);

  /**
   * Resolve a tenant's status list by its {@code listCode} and return its signed artifact,
   * publishing it first if it has never been published or has fallen stale (spec FS-1.3 D3's
   * lazy-publish fallback) — backs {@code tenant.web.TenantStatusListController}'s {@code GET
   * /sl/{tenantSlug}/{listCode}} (spec FS-2.1 D8, relocated from {@code status.web} so the {@code
   * status} module never needs a reverse dependency on {@code tenant :: api} to resolve the slug in
   * that path — the caller resolves {@code tenantSlug} to {@code tenantId} itself and passes it in
   * here).
   *
   * @param tenantId the tenant that owns the list
   * @param listCode the list's code
   * @return the signed artifact + version, or {@link Optional#empty()} if no such list exists for
   *     this tenant
   */
  Optional<StatusListArtifact> findArtifact(UUID tenantId, String listCode);
}
