/**
 * Holder module — pseudonymous holder identity registry.
 *
 * <p><b>Responsibilities:</b> register and resolve pseudonymous holder references ({@code
 * pseudoRef}), maintain holder–credential binding metadata without storing PII (P1 rule).
 *
 * <p><b>Exposed API:</b> {@code api/} sub-package — {@link
 * sy.khatm.platform.holder.api.HolderDirectory#ensureHolder} finds or registers a holder by
 * pseudonymous reference (KH-0.2.1); {@code findByPseudoRef} and {@code findById} (KH-2.8.2, the
 * idempotent-replay {@code holderRef} lookup) read without registering.
 *
 * <p><b>Published events:</b> (none yet)
 *
 * <p><b>Tables owned:</b> {@code holder}
 *
 * <p><b>Status:</b> minimal persistence + find-or-create API (KH-0.2.1); wallet key-binding ({@code
 * wallet_jwk}) deferred to Phase 3.
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.holder;
