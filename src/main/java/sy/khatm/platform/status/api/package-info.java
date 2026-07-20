/**
 * Public API surface of the status module.
 *
 * <p>Only types in this package may be referenced by other modules. All other sub-packages of
 * {@code sy.khatm.platform.status} are module-private.
 *
 * <p><b>Contains:</b> {@link sy.khatm.platform.status.api.StatusListAllocator} (KH-0.2.1), {@link
 * sy.khatm.platform.status.api.StatusListRevoker} + {@link
 * sy.khatm.platform.status.api.StatusListLookup} (KH-1.3, spec FS-1.3), and the {@code
 * StatusAllocation}/{@code StatusListRef} records they return.
 */
@org.springframework.modulith.NamedInterface("api")
package sy.khatm.platform.status.api;
