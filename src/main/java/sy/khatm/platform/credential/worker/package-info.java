/**
 * Worker-role components of the credential module — background jobs that run only when {@code
 * khatm.worker.enabled=true} (the {@code worker} runtime image, ADR-09), never in {@code api}.
 *
 * <p>This is a sub-package of the {@code credential} module, <em>not</em> a separate Spring
 * Modulith module: the runtime {@code api}/{@code worker} roles are a profile split from one image,
 * not a module boundary. The root-package {@code sy.khatm.platform.ModulithBoundariesTest} still
 * governs the single {@code credential} module's package reach.
 *
 * <p><b>Contains:</b> {@link sy.khatm.platform.credential.worker.ClaimCodeExpiryWorker} — the
 * scheduled {@code disclosures_enc} expiry-zeroing sweep (FS-0.2 §3.7).
 */
package sy.khatm.platform.credential.worker;
