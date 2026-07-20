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
 * sy.khatm.platform.status.api.StatusListLookup#findRef} (KH-1.3).
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
