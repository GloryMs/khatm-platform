/**
 * Ledger module — append-only audit ledger with Merkle proof support.
 *
 * <p><b>Responsibilities:</b> receive audit events from all modules, append them to the immutable
 * ledger, compute and expose Merkle inclusion proofs for any event.
 *
 * <p><b>Exposed API:</b> (none yet — KH-1.x)
 *
 * <p><b>Published events:</b> (none — ledger is a sink)
 *
 * <p><b>Tables owned:</b> {@code audit_log}, {@code merkle_node}
 *
 * <p><b>Status:</b> stub — implementation deferred to KH-1.x.
 */
package sy.khatm.platform.ledger;
