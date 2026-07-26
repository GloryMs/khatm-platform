/**
 * Status module — credential status list management (spec FS-1.3).
 *
 * <p><b>Responsibilities:</b> maintain per-credential status bits in a compact, gzip-compressed
 * bitstring; flip a bit and bump the list version inside a revoke transaction; sign and publish the
 * bitstring as a compact JWS artifact; serve the public status-list endpoint.
 *
 * <p><b>Exposed API:</b> {@code api/} sub-package — {@link
 * sy.khatm.platform.status.api.StatusListAllocator#allocate} (KH-0.2.1), {@link
 * sy.khatm.platform.status.api.StatusListRevoker#revoke} + {@link
 * sy.khatm.platform.status.api.StatusListLookup#findRef} (KH-1.3), and two KH-2.1 additions (spec
 * FS-2.1 D6/D8): {@link sy.khatm.platform.status.api.StatusListAllocator#ensureList} (the
 * tenant-onboarding admin plane's "create this new tenant's default list" step — explicit {@code
 * tenantId}, not {@code TenantContext.current()}, since the onboarding caller's own tenant is never
 * the tenant being onboarded) and {@link
 * sy.khatm.platform.status.api.StatusListLookup#findArtifact} (backs {@code
 * tenant.web.TenantStatusListController}'s {@code GET /sl/{tenantSlug}/{listCode}}, relocated out
 * of this module's own {@code web} so this module never needs a reverse dependency on {@code tenant
 * :: api} to resolve the path's slug — would be a Modulith cycle, since {@code tenant} already
 * depends on {@code ensureList} for onboarding).
 *
 * <p><b>Published events:</b> {@link sy.khatm.platform.status.events.StatusListChanged} (KH-1.3) —
 * fired inside the revoke transaction's bit-flip, externalized to the {@code
 * khatm.credential.events} stream.
 *
 * <p><b>Tables owned:</b> {@code status_list} (V1 + V3's {@code signed_artifact}/{@code
 * artifact_version} columns).
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.status;
