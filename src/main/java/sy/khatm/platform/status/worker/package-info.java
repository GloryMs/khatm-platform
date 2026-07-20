/**
 * Worker-role components of the status module — background jobs that run only when {@code
 * khatm.worker.enabled=true} (the {@code worker} runtime image, ADR-09), never in {@code api}.
 *
 * <p>This is a sub-package of the {@code status} module, <em>not</em> a separate Spring Modulith
 * module: the runtime {@code api}/{@code worker} roles are a profile split from one image, not a
 * module boundary. The root-package {@code sy.khatm.platform.ModulithBoundariesTest} still governs
 * the single {@code status} module's package reach.
 *
 * <p><b>Contains:</b> {@link sy.khatm.platform.status.worker.StatusListChangedHandler} — the
 * near-real-time consumer for {@link sy.khatm.platform.status.events.StatusListChanged}; {@link
 * sy.khatm.platform.status.worker.StatusListPublishSweepWorker} — the periodic catch-up safety net
 * (spec FS-1.3 D3/D5).
 */
package sy.khatm.platform.status.worker;
